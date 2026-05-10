package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Records audio using the low-level Android pipeline:
 * {@link AudioRecord} (44.1 kHz mono PCM 16-bit) → {@link MediaCodec} (AAC LC)
 * → {@link MediaMuxer} (MPEG_4). A background thread reads PCM, feeds the
 * encoder, and drains encoded AAC into the muxer.
 *
 * Each pause/resume cycle finalizes the active muxer to its own segment file.
 * Those segment files are concatenated (with PTS rebase) at
 * {@link #stopRecording()} via {@link AudioFileProcessor#concatenateAudioFiles}.
 *
 * Preview-while-paused returns the latest finalized segment unchanged, so
 * pause→preview→resume cycles stay O(1) regardless of recording length.
 */
class RecordingManager implements AudioManager.OnAudioFocusChangeListener {
    interface RecordingCallback {
        void onStatusChanged(String status, String reason, String message, Boolean recoverable);
        void onError(String message);
        void onDurationChanged(double duration);
    }

    private static final String TAG = "RecordingManager";
    private static final String PREVIEW_FILE_PREFIX = "paused_recording_preview_";
    private static final long THREAD_JOIN_TIMEOUT_MS = 3000L;

    private final RecordingCallback callback;
    private final Context context;
    private final Handler mainHandler;

    // Audio focus management
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;
    private boolean focusLossPaused = false;

    // Duration monitoring
    private Timer durationTimer;
    private double currentDuration = 0.0;
    private volatile boolean isDurationMonitoring = false;
    private volatile boolean isDurationPaused = false;

    private boolean isRecording = false;
    private boolean isPaused = false;

    // Segment-based output state
    private File segmentDirectory;
    private File currentSegmentFile;
    private int segmentIndex = 0;
    private String finalOutputPath;
    private File previewFile;
    private final List<File> segments = new ArrayList<>();

    // PCM/AAC pipeline
    private final PcmRecordingEngine engine = new PcmRecordingEngine();

    // Long-lived "main" output file populated continuously by the engine for
    // the entire session. Stop just closes the muxer; no full-session walk.
    private File mainOutputFile;

    RecordingManager(Context context, RecordingCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());

        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
            )
            .setOnAudioFocusChangeListener(this)
            .build();
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void startRecording(StartOptions options) {
        if (isRecording) return;
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (callback != null) callback.onError("RECORD_AUDIO permission not granted");
                return;
            }

            if (!requestAudioFocus()) {
                Log.w(TAG, "Failed to gain audio focus, but continuing with recording");
            }

            try {
                Intent serviceIntent = new Intent(context, AudioRecordingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
            } catch (Exception e) {
                Log.w(TAG, "Failed to start foreground service", e);
            }

            String outputPath = options != null ? options.path : null;

            cleanupSegmentArtifacts();
            cleanupPausedPlaybackPreview();
            segmentIndex = 0;
            segments.clear();
            synchronized (this) {
                currentDuration = 0.0;
            }

            finalOutputPath = resolveFinalOutputPath(outputPath);
            ensureFinalOutputParentExists(finalOutputPath);
            segmentDirectory = createSegmentDirectory(finalOutputPath);

            // Open the long-lived main muxer FIRST so the engine can add its
            // track on the first INFO_OUTPUT_FORMAT_CHANGED.
            mainOutputFile = new File(segmentDirectory, "main_session.m4a");
            engine.openMainMuxer(mainOutputFile);

            currentSegmentFile = nextSegmentFile();
            engine.resetSession();
            engine.openSegment(currentSegmentFile);

            isRecording = true;
            isPaused = false;

            try {
                startDurationMonitoring();
                if (callback != null) {
                    callback.onStatusChanged("recording", "user", null, null);
                }
            } catch (Exception monitoringError) {
                Log.e(TAG, "Failed to start monitoring after recording start", monitoringError);
                try { stopDurationMonitoring(); } catch (Exception ignored) {}
                engine.shutdown();
                isRecording = false;
                isPaused = false;
                if (callback != null) callback.onError("Failed to start monitoring: " + monitoringError.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            if (callback != null) callback.onError(e.getMessage());
            engine.shutdown();
            isRecording = false;
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void stopRecording() {
        stopRecordingAndWaitForFile();
    }

    /**
     * Stop recording, finalize the active segment, then concatenate every
     * segment captured during this session into the final output file.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    String stopRecordingAndWaitForFile() {
        if (!isRecording) return finalOutputPath;

        try {
            // Drain and finalize the active segment unless we were already paused.
            if (!isPaused) {
                File closed = engine.closeSegment();
                acceptClosedSegment(closed);
            }
        } catch (Exception e) {
            Log.w(TAG, "Error finalizing active segment on stop", e);
        }

        stopDurationMonitoring();

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            context.stopService(serviceIntent);
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop foreground service", e);
        }

        abandonAudioFocus();

        // Close the long-lived main muxer. By this point every encoded AAC
        // frame produced during the session has already been written to it
        // in parallel with the segment muxers, so this is just `moov`-write
        // overhead — O(1) regardless of recording length.
        File mainFile = null;
        try {
            mainFile = engine.closeMainMuxer();
        } catch (Exception e) {
            Log.w(TAG, "closeMainMuxer failed", e);
        }

        String resultPath = finalOutputPath;
        try {
            File outFile = new File(finalOutputPath);
            if (mainFile != null && mainFile.exists() && mainFile.length() > 0) {
                // Fast path: the running session output is already the answer.
                moveOrCopy(mainFile, outFile);
                resultPath = outFile.getAbsolutePath();
            } else if (segments.isEmpty()) {
                if (previewFile != null && previewFile.exists() && previewFile.length() > 0) {
                    moveOrCopy(previewFile, outFile);
                    resultPath = outFile.getAbsolutePath();
                } else {
                    Log.w(TAG, "No audio captured for stopRecording");
                }
            } else if (segments.size() == 1) {
                // Fallback if the main muxer somehow produced no usable file.
                moveOrCopy(segments.get(0), outFile);
                resultPath = outFile.getAbsolutePath();
            } else {
                AudioFileProcessor.concatenateAudioFiles(segments, outFile);
                resultPath = outFile.getAbsolutePath();
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to finalize recording", e);
            if (callback != null) callback.onError("Failed to assemble recording: " + e.getMessage());
        }

        cleanupSegmentArtifacts();
        cleanupPausedPlaybackPreview();

        isRecording = false;
        isPaused = false;
        if (callback != null) callback.onStatusChanged("stopped", "user", null, null);

        return resultPath;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void pauseRecording() {
        pauseRecordingInternal("user");
    }

    private void pauseRecordingInternal(String reason) {
        if (!isRecording || isPaused) return;
        try {
            File closed = engine.closeSegment();
            acceptClosedSegment(closed);

            isPaused = true;
            pauseDurationMonitoring();

            if (callback != null) callback.onStatusChanged("paused", reason, null, null);
        } catch (Exception e) {
            Log.w(TAG, "Error pausing recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void resumeRecording() {
        if (!isRecording || !isPaused) return;
        try {
            cleanupPausedPlaybackPreview();
            currentSegmentFile = nextSegmentFile();
            engine.openSegment(currentSegmentFile);

            isPaused = false;
            focusLossPaused = false;
            resumeDurationMonitoring();

            if (callback != null) callback.onStatusChanged("recording", "user", null, null);
        } catch (Exception e) {
            Log.w(TAG, "Error resuming recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void resetRecording() {
        if (!isRecording) return;
        try {
            try {
                engine.shutdown();
            } catch (Exception ignored) {}

            cleanupSegmentArtifacts();
            cleanupPausedPlaybackPreview();
            segments.clear();
            segmentIndex = 0;
            engine.resetSession();
            segmentDirectory = createSegmentDirectory(finalOutputPath);

            // Reopen a fresh main muxer so the next resume can keep streaming
            // into it.
            mainOutputFile = new File(segmentDirectory, "main_session.m4a");
            try {
                engine.openMainMuxer(mainOutputFile);
            } catch (IOException ioe) {
                Log.w(TAG, "Failed to reopen main muxer after reset", ioe);
            }

            stopDurationMonitoring();
            synchronized (this) {
                currentDuration = 0.0;
            }

            isPaused = true;

            if (callback != null) callback.onStatusChanged("paused", "user", null, null);
        } catch (Exception e) {
            Log.w(TAG, "Error resetting recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Promote the most recently finalized segment to a preview file suitable
     * for ExoPlayer playback while recording is paused. O(1): just copies the
     * last segment, never re-concatenates the cumulative recording.
     */
    File prepareForPausedPlayback() throws IOException {
        if (!isRecording || !isPaused) {
            throw new IOException("playPausedRecording requires an active, paused recording");
        }
        if (segments.isEmpty()) {
            throw new IOException("No audio has been recorded yet to play back");
        }

        File source = segments.get(segments.size() - 1);
        if (source == null || !source.exists() || source.length() == 0) {
            throw new IOException("Latest recording segment is empty");
        }

        File previewDir = (segmentDirectory != null && segmentDirectory.getParentFile() != null)
                ? segmentDirectory.getParentFile()
                : context.getFilesDir();
        File newPreview = new File(previewDir, PREVIEW_FILE_PREFIX + System.currentTimeMillis() + ".m4a");
        AudioFileProcessor.copyFile(source, newPreview);

        File prior = previewFile;
        previewFile = newPreview;
        if (prior != null && !prior.equals(newPreview) && prior.exists()) {
            //noinspection ResultOfMethodCallIgnored
            prior.delete();
        }
        return previewFile;
    }

    /**
     * Delete the temporary preview file (if any) created by
     * {@link #prepareForPausedPlayback()}.
     */
    void cleanupPausedPlaybackPreview() {
        if (previewFile != null) {
            try {
                if (previewFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    previewFile.delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to delete preview file", e);
            }
            previewFile = null;
        }
    }

    String getPausedPlaybackPreviewPath() {
        return previewFile != null ? previewFile.getAbsolutePath() : null;
    }

    private void acceptClosedSegment(File closed) {
        if (closed == null) return;
        if (closed.exists() && closed.length() > 0) {
            // The encoded AAC frames already went into the long-lived main
            // muxer in parallel. The segment file is kept purely as a
            // pause-snapshot for prepareForPausedPlayback().
            segments.add(closed);
        } else {
            try {
                if (closed.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    closed.delete();
                }
            } catch (Exception ignored) {}
        }
        currentSegmentFile = null;
    }

    private void moveOrCopy(File src, File dst) throws IOException {
        if (src == null || !src.exists()) {
            throw new IOException("Source file missing: " + src);
        }
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        if (dst.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
        }
        if (src.renameTo(dst)) return;
        AudioFileProcessor.copyFile(src, dst);
        //noinspection ResultOfMethodCallIgnored
        src.delete();
    }

    private void cleanupSegmentArtifacts() {
        // Do NOT touch the long-lived main muxer here. This method is also
        // used by mid-session cleanup paths (preview/resume/reset setup), and
        // closing the main muxer before final stop puts MediaMuxer into a
        // native "already stopped" state. Final stop calls closeMainMuxer();
        // reset/error paths call engine.shutdown().
        // mainOutputFile itself lives under segmentDirectory and will be
        // removed by the directory cleanup below once the main muxer has
        // been explicitly closed/discarded by the caller.
        mainOutputFile = null;

        for (File f : segments) {
            try {
                if (f != null && f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            } catch (Exception ignored) {}
        }
        segments.clear();

        if (currentSegmentFile != null && currentSegmentFile.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                currentSegmentFile.delete();
            } catch (Exception ignored) {}
        }
        currentSegmentFile = null;

        if (segmentDirectory != null) {
            try {
                File[] leftovers = segmentDirectory.listFiles();
                if (leftovers != null) {
                    for (File f : leftovers) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                segmentDirectory.delete();
            } catch (Exception ignored) {}
            segmentDirectory = null;
        }
    }

    private File nextSegmentFile() {
        if (segmentDirectory == null) {
            segmentDirectory = createSegmentDirectory(finalOutputPath);
        }
        String name = String.format(Locale.US, "segment_%03d.m4a", segmentIndex++);
        return new File(segmentDirectory, name);
    }

    private File createSegmentDirectory(String finalPath) {
        File parent;
        if (finalPath != null) {
            File outFile = new File(finalPath);
            File outParent = outFile.getParentFile();
            parent = outParent != null ? outParent : context.getFilesDir();
        } else {
            parent = context.getFilesDir();
        }
        File dir = new File(parent, ".audio_engine_segments_" + System.currentTimeMillis());
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private String resolveFinalOutputPath(String outputPath) {
        String resolved;
        if (outputPath != null && !outputPath.isEmpty()) {
            resolved = getNormalizedPath(outputPath);
        } else {
            String dataDir = context.getFilesDir().getAbsolutePath();
            resolved = dataDir + "/recording_" + System.currentTimeMillis() + ".m4a";
        }
        File outFile = new File(resolved);
        if (outFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
        }
        return resolved;
    }

    private void ensureFinalOutputParentExists(String path) {
        if (path == null) return;
        File outFile = new File(path);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
    }

    private String getNormalizedPath(String outputPath) {
        String baseDir = context.getFilesDir().getAbsolutePath();
        String normalizedPath;
        if (outputPath.startsWith("file://")) {
            normalizedPath = outputPath.substring(7);
        } else if (outputPath.startsWith("/")) {
            normalizedPath = baseDir + "/" + outputPath.substring(1);
        } else {
            normalizedPath = baseDir + "/" + outputPath;
        }
        return normalizedPath;
    }

    // Duration monitoring
    private void startDurationMonitoring() {
        stopDurationMonitoring();
        isDurationMonitoring = true;
        isDurationPaused = false;

        durationTimer = new Timer();
        durationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isDurationPaused && isDurationMonitoring) {
                    double duration;
                    synchronized (RecordingManager.this) {
                        currentDuration += 1.0;
                        duration = currentDuration;
                    }
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onDurationChanged(duration);
                        }
                    });
                }
            }
        }, 1000, 1000);
    }

    private void stopDurationMonitoring() {
        if (durationTimer != null) {
            durationTimer.cancel();
            durationTimer = null;
        }
        isDurationMonitoring = false;
        isDurationPaused = false;
    }

    private void pauseDurationMonitoring() {
        isDurationPaused = true;
    }

    private void resumeDurationMonitoring() {
        if (!isDurationMonitoring) {
            startDurationMonitoring();
        } else {
            isDurationPaused = false;
        }
    }

    // Audio focus management
    private boolean requestAudioFocus() {
        if (audioManager == null) return false;
        try {
            int result;
            if (audioFocusRequest != null) {
                result = audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                return false;
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            return hasAudioFocus;
        } catch (Exception e) {
            Log.e(TAG, "Error requesting audio focus", e);
            return false;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !hasAudioFocus) return;
        try {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
            hasAudioFocus = false;
        } catch (Exception e) {
            Log.e(TAG, "Error abandoning audio focus", e);
        }
    }

    @SuppressWarnings("MissingPermission")
    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.d(TAG, "Audio focus changed: " + focusChange);

        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (focusLossPaused && isRecording && isPaused) {
                    // App layer decides whether to resume; surface the event.
                    if (callback != null) {
                        callback.onStatusChanged("focusRegained", "interruption", null, null);
                    }
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (isRecording && !isPaused) {
                    focusLossPaused = true;
                    pauseRecordingInternal("interruption");
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Recording continues — nothing to do.
                break;

            default:
                break;
        }
    }

    static class StartOptions {
        String path;
        /**
         * Kept for back-compat. Under the new engine every pause already
         * finalizes a real .m4a, so paused-playback preview is always
         * available regardless of this flag.
         */
        Boolean enablePausedPreview;
    }

    StatusInfo getStatus() {
        synchronized (this) {
            String statusString;
            if (!isRecording) {
                statusString = "idle";
            } else if (isPaused) {
                statusString = "paused";
            } else {
                statusString = "recording";
            }
            return new StatusInfo(statusString, currentDuration, finalOutputPath);
        }
    }

    record StatusInfo(String status, double duration, String path) {}

    /**
     * Pre-warm hook. The PCM engine starts cheaply enough that no real
     * pre-warm is necessary; this is a no-op kept for plugin compatibility.
     */
    public void preWarmRecorder() {
        // Intentionally empty — AudioRecord/MediaCodec startup is fast.
    }

    // -----------------------------------------------------------------
    // PCM → AAC → MP4 pipeline
    // -----------------------------------------------------------------

    /**
     * Owns the {@link AudioRecord} → {@link MediaCodec} → {@link MediaMuxer}
     * pipeline for a single segment. {@link #openSegment(File)} starts a fresh
     * reader thread; {@link #closeSegment()} signals EOS, drains, and
     * finalizes the segment file. {@link #resetSession()} clears the
     * session-wide PTS counter so a brand new recording starts at zero.
     */
    private final class PcmRecordingEngine {
        private static final String MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
        private static final int SAMPLE_RATE = AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
        private static final int CHANNEL_COUNT = AudioEngineConfig.Recording.DEFAULT_CHANNELS;
        private static final int BIT_RATE = AudioEngineConfig.Recording.DEFAULT_BITRATE;
        private static final int BITS_PER_SAMPLE = 16;
        private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
        private static final int AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT;

        private AudioRecord audioRecord;
        private MediaCodec encoder;
        private MediaMuxer muxer;
        private int trackIndex = -1;
        private boolean muxerStarted;
        private File outputFile;

        // Long-lived "main" muxer that receives every encoded AAC frame for
        // the entire session in parallel with the per-segment muxer. At stop
        // we just close it — no full-session walk. The segment muxer is kept
        // around purely as a per-pause snapshot for paused-playback preview.
        private MediaMuxer mainMuxer;
        private int mainTrackIndex = -1;
        private boolean mainMuxerStarted;
        private boolean mainMuxerHasSamples;
        private File mainOutputFile;
        // Last PTS written to mainMuxer. The main muxer outlives encoder
        // instances (one per pause/resume), and a fresh encoder can emit its
        // first frame at a PTS ≤ the previous encoder's last frame, which
        // makes MediaMuxer.writeSampleData fail with IllegalStateException.
        private long lastMainPtsUs = -1L;

        private Thread readerThread;
        private volatile boolean running;
        private volatile boolean eosRequested;

        // Session-wide PCM frame counter — never reset between segments so
        // PTS stays monotonic across pause/resume boundaries.
        private long totalFramesRead;

        void resetSession() {
            totalFramesRead = 0L;
        }

        /**
         * Open the long-lived main muxer that will receive every encoded AAC
         * frame for the rest of this session. Must be called before
         * {@link #openSegment(File)} on the first segment so that the track
         * is added when the encoder fires INFO_OUTPUT_FORMAT_CHANGED.
         */
        void openMainMuxer(File mainFile) throws IOException {
            if (mainMuxer != null) {
                Log.w(TAG, "openMainMuxer: already open; closing first");
                releaseMainMuxer();
            }
            mainOutputFile = mainFile;
            File parent = mainFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try {
                mainMuxer = new MediaMuxer(mainFile.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                throw new IOException("Failed to open main MediaMuxer at "
                        + mainFile.getAbsolutePath(), e);
            }
            mainMuxerStarted = false;
            mainMuxerHasSamples = false;
            mainTrackIndex = -1;
            lastMainPtsUs = -1L;
        }

        /**
         * Stop and release the main muxer, returning the finalized output
         * file (or {@code null} if it was never started / has no data).
         * Caller is responsible for moving the returned file to the final
         * destination.
         */
        File closeMainMuxer() {
            if (mainMuxer == null) {
                return null;
            }
            File f = mainOutputFile;
            try {
                if (mainMuxerStarted && mainMuxerHasSamples) {
                    try {
                        mainMuxer.stop();
                    } catch (IllegalStateException ise) {
                        // Some Android builds throw this if the native muxer
                        // was implicitly stopped after a prior failure. Treat
                        // close as idempotent; fallback assembly below will
                        // cover the rare case where the file is unusable.
                        String message = ise.getMessage();
                        if (message != null && message.contains("stopped already")) {
                            Log.d(TAG, "main muxer already stopped during close");
                        } else {
                            Log.w(TAG, "main muxer.stop", ise);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "main muxer.stop", e);
                    }
                }
            } finally {
                try { mainMuxer.release(); } catch (Exception ignored) {}
                mainMuxer = null;
            }
            boolean produced = mainMuxerStarted && mainMuxerHasSamples
                    && f != null && f.exists() && f.length() > 0;
            mainMuxerStarted = false;
            mainMuxerHasSamples = false;
            mainTrackIndex = -1;
            lastMainPtsUs = -1L;
            mainOutputFile = null;
            if (!produced) {
                if (f != null && f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
                return null;
            }
            return f;
        }

        private void releaseMainMuxer() {
            if (mainMuxer != null) {
                try {
                    if (mainMuxerStarted && mainMuxerHasSamples) {
                        try { mainMuxer.stop(); } catch (Exception ignored) {}
                    }
                } finally {
                    try { mainMuxer.release(); } catch (Exception ignored) {}
                    mainMuxer = null;
                }
            }
            mainMuxerStarted = false;
            mainMuxerHasSamples = false;
            mainTrackIndex = -1;
            lastMainPtsUs = -1L;
            if (mainOutputFile != null && mainOutputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                mainOutputFile.delete();
            }
            mainOutputFile = null;
        }

        /** Open a new segment: AudioRecord + MediaCodec + MediaMuxer + reader thread. */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        void openSegment(File segmentFile) throws IOException {
            if (readerThread != null && readerThread.isAlive()) {
                Log.w(TAG, "openSegment called while a reader thread is still alive — ignoring");
                return;
            }
            this.outputFile = segmentFile;
            this.muxerStarted = false;
            this.trackIndex = -1;
            this.eosRequested = false;

            int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING);
            if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
                minBuffer = 4096;
            }
            int audioBufferBytes = Math.max(minBuffer * 2, 8192);

            int source = chooseAudioSource();
            audioRecord = new AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_ENCODING, audioBufferBytes);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseQuietly();
                throw new IOException("AudioRecord failed to initialize (state=" + audioRecord.getState() + ")");
            }

            try {
                MediaFormat fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNEL_COUNT);
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);

                encoder = MediaCodec.createEncoderByType(MIME_AAC);
                encoder.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();

                muxer = new MediaMuxer(segmentFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (Exception e) {
                releaseQuietly();
                throw new IOException("Failed to set up audio encoder/muxer: " + e.getMessage(), e);
            }

            try {
                audioRecord.startRecording();
            } catch (IllegalStateException ise) {
                releaseQuietly();
                throw new IOException("AudioRecord.startRecording failed", ise);
            }

            running = true;
            int pcmBufferBytes = audioBufferBytes;
            readerThread = new Thread(() -> readerLoop(pcmBufferBytes), "AudioEngine-Reader");
            readerThread.setPriority(Thread.MAX_PRIORITY);
            readerThread.start();
        }

        /**
         * Signal EOS, wait for the reader to drain the encoder, then stop and
         * release codec/muxer/AudioRecord. Returns the finalized segment file
         * (caller may delete it if empty).
         */
        File closeSegment() {
            if (readerThread == null) return outputFile;

            // Order matters: request EOS first, do NOT stop the reader loop
            // early or the EOS marker never reaches the encoder.
            eosRequested = true;
            try {
                readerThread.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            // If the thread didn't exit cleanly, force the loop to bail out
            // and proceed with cleanup anyway.
            running = false;
            readerThread = null;

            // Stop the muxer only if we actually started it (i.e. the encoder
            // produced its INFO_OUTPUT_FORMAT_CHANGED). Calling stop() on a
            // never-started muxer throws IllegalStateException.
            try {
                if (muxer != null && muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "muxer.stop failed", e);
            }
            releaseQuietly();

            File closed = outputFile;
            outputFile = null;
            return closed;
        }

        /** Hard stop without finalizing the muxer (used by reset/error paths). */
        void shutdown() {
            if (readerThread != null) {
                running = false;
                eosRequested = true;
                try {
                    readerThread.join(THREAD_JOIN_TIMEOUT_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                readerThread = null;
            }
            releaseQuietly();
            outputFile = null;
            // The main muxer's lifetime spans pause/resume; only the hard-
            // stop path tears it down (closeMainMuxer is the normal exit).
            releaseMainMuxer();
        }

        private void readerLoop(int pcmBufferBytes) {
            byte[] pcm = new byte[pcmBufferBytes];
            try {
                while (running) {
                    int n = 0;
                    if (!eosRequested) {
                        n = audioRecord.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                        if (n < 0) {
                            Log.w(TAG, "AudioRecord.read returned " + n + "; signalling EOS");
                            eosRequested = true;
                            n = 0;
                        }
                    }

                    int inIdx = encoder.dequeueInputBuffer(10_000L);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = encoder.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            inBuf.clear();
                            if (n > 0) inBuf.put(pcm, 0, n);
                        }
                        long ptsUs = totalFramesRead * 1_000_000L / SAMPLE_RATE;
                        int flags = eosRequested ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0;
                        encoder.queueInputBuffer(inIdx, 0, Math.max(0, n), ptsUs, flags);
                        if (n > 0) {
                            totalFramesRead += n / (CHANNEL_COUNT * (BITS_PER_SAMPLE / 8));
                        }
                    }

                    if (drainEncoder()) break;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Reader thread error", t);
                final String message = t.getMessage();
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Recording engine error: " + message);
                });
            } finally {
                running = false;
                try {
                    if (audioRecord != null) {
                        try { audioRecord.stop(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }

        /**
         * Drain encoded output to the muxer. Handles INFO_OUTPUT_FORMAT_CHANGED
         * (track add + muxer.start) and EOS. Returns true once a buffer with
         * BUFFER_FLAG_END_OF_STREAM has been seen.
         */
        private boolean drainEncoder() {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int outIdx = encoder.dequeueOutputBuffer(info, 10_000L);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    return false;
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerStarted) {
                        Log.w(TAG, "Format changed twice; ignoring");
                        continue;
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    trackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                    muxerStarted = true;
                    // The main muxer takes its track from the FIRST encoder of
                    // the session. Subsequent encoders (after pause/resume)
                    // produce equivalent format settings, so we keep writing
                    // to the existing track.
                    if (mainMuxer != null && !mainMuxerStarted) {
                        try {
                            mainTrackIndex = mainMuxer.addTrack(newFormat);
                            mainMuxer.start();
                            mainMuxerStarted = true;
                        } catch (Exception e) {
                            Log.w(TAG, "main muxer addTrack/start failed", e);
                            mainTrackIndex = -1;
                            mainMuxerStarted = false;
                        }
                    }
                } else if (outIdx < 0) {
                    // INFO_OUTPUT_BUFFERS_CHANGED is deprecated/ignored.
                    continue;
                } else {
                    ByteBuffer outBuf = encoder.getOutputBuffer(outIdx);
                    boolean isData = outBuf != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    if (isData) {
                        if (muxerStarted) {
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            try {
                                muxer.writeSampleData(trackIndex, outBuf, info);
                            } catch (Exception e) {
                                Log.w(TAG, "segment muxer writeSampleData failed", e);
                            }
                        }
                        if (mainMuxerStarted && mainTrackIndex >= 0) {
                            // ByteBuffer position was advanced by the previous
                            // writeSampleData; reset it before the second write.
                            outBuf.position(info.offset);
                            outBuf.limit(info.offset + info.size);
                            // MediaMuxer requires strictly monotonic PTS per
                            // track. Across pause/resume we keep the same
                            // track but the encoder is recreated; its first
                            // frame can land at or slightly before the prior
                            // encoder's last PTS. Clamp forward by 1µs to
                            // keep the muxer in a writable state.
                            long originalPts = info.presentationTimeUs;
                            if (originalPts <= lastMainPtsUs) {
                                info.presentationTimeUs = lastMainPtsUs + 1;
                            }
                            try {
                                mainMuxer.writeSampleData(mainTrackIndex, outBuf, info);
                                lastMainPtsUs = info.presentationTimeUs;
                                mainMuxerHasSamples = true;
                            } catch (Exception e) {
                                Log.w(TAG, "main muxer writeSampleData failed", e);
                            } finally {
                                info.presentationTimeUs = originalPts;
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return true;
                    }
                }
            }
        }

        private void releaseQuietly() {
            if (audioRecord != null) {
                try { audioRecord.stop(); } catch (Exception ignored) {}
                try { audioRecord.release(); } catch (Exception ignored) {}
                audioRecord = null;
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
                encoder = null;
            }
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignored) {}
                muxer = null;
            }
            muxerStarted = false;
            trackIndex = -1;
        }

        @SuppressWarnings("MissingPermission")
        private int chooseAudioSource() {
            return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
    }
}
