package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Records audio in segments. Each pause/resume cycle finalizes the current
 * MediaRecorder output to its own segment file; on stop the segments are
 * concatenated into a single output file at the path supplied to startRecording.
 *
 * Segment-based recording lets the captured audio be played back while the
 * recording is paused (see {@link #prepareForPausedPlayback()}), since each
 * segment is fully written to disk before pause returns.
 */
class RecordingManager implements AudioManager.OnAudioFocusChangeListener {
    interface RecordingCallback {
        void onStatusChanged(String status, String reason, String message, Boolean recoverable);
        void onError(String message);
        void onDurationChanged(double duration);
    }

    private static final String TAG = "RecordingManager";
    private static final String PREVIEW_FILE_PREFIX = "paused_recording_preview_";

    private final RecordingCallback callback;
    private final Context context;
    private final Handler mainHandler;

    private MediaRecorder mediaRecorder;

    // Audio focus management
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    // Duration monitoring
    private Timer durationTimer;
    private double currentDuration = 0.0;
    private volatile boolean isDurationMonitoring = false;
    private volatile boolean isDurationPaused = false;

    private boolean isRecording = false;
    private boolean isPaused = false;

    // Segment-based recording state
    private final List<File> segments = new ArrayList<>();
    private File segmentDirectory;
    private File currentSegmentFile;
    private int segmentIndex = 0;
    private String finalOutputPath;
    private File previewFile;

    private StartOptions lastOptions;

    RecordingManager(Context context, RecordingCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Initialize audio manager
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Create audio focus request for recording
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
                Log.d(TAG, "Foreground service started for recording");
            } catch (Exception e) {
                Log.w(TAG, "Failed to start foreground service", e);
            }

            lastOptions = options;
            String outputPath = options != null ? options.path : null;

            // Reset segment state for a fresh session.
            cleanupSegments();
            cleanupPausedPlaybackPreview();
            segmentIndex = 0;
            synchronized (this) {
                currentDuration = 0.0;
            }

            finalOutputPath = resolveFinalOutputPath(outputPath);
            ensureFinalOutputParentExists(finalOutputPath);

            // Place segments in their own directory next to the final output so
            // we can clean them up atomically on stop/reset.
            segmentDirectory = createSegmentDirectory(finalOutputPath);
            currentSegmentFile = nextSegmentFile();

            if (mediaRecorder == null) {
                Log.d(TAG, "Creating new MediaRecorder");
                mediaRecorder = new MediaRecorder();
                setBestAudioSource(mediaRecorder);
            } else {
                Log.d(TAG, "Reusing pre-warmed MediaRecorder");
            }

            configureRecorderCommon(mediaRecorder, currentSegmentFile.getAbsolutePath());
            try {
                mediaRecorder.prepare();
                mediaRecorder.start();
            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException starting MediaRecorder", se);
                if (callback != null) callback.onError("SecurityException: RECORD_AUDIO denied or restricted");
                cleanupMediaRecorder();
                isRecording = false;
                return;
            }

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
                cleanupMediaRecorder();
                isRecording = false;
                isPaused = false;
                if (callback != null) callback.onError("Failed to start monitoring: " + monitoringError.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            if (callback != null) callback.onError(e.getMessage());
            cleanupMediaRecorder();
            isRecording = false;
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void stopRecording() {
        stopRecordingAndWaitForFile();
    }

    /**
     * Stop recording, finalize the active segment, concatenate all segments
     * into the final output path, and return that path.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    String stopRecordingAndWaitForFile() {
        if (!isRecording) return finalOutputPath;

        // Finalize whatever segment is currently being written.
        try {
            finalizeActiveSegmentForStop();
        } catch (Exception e) {
            Log.w(TAG, "Error finalizing active segment on stop", e);
        }

        stopDurationMonitoring();

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            context.stopService(serviceIntent);
            Log.d(TAG, "Foreground service stopped");
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop foreground service", e);
        }

        abandonAudioFocus();

        // Combine segments into the final output file.
        String resultPath = finalOutputPath;
        try {
            if (!segments.isEmpty() && finalOutputPath != null) {
                File outFile = new File(finalOutputPath);
                AudioFileProcessor.concatenateAudioFiles(new ArrayList<>(segments), outFile);
                resultPath = outFile.getAbsolutePath();
            } else {
                Log.w(TAG, "No segments captured for stopRecording");
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to concatenate recording segments", e);
            if (callback != null) callback.onError("Failed to assemble recording: " + e.getMessage());
        }

        // Always clean up segments and any preview file regardless of concat outcome.
        cleanupSegments();
        cleanupPausedPlaybackPreview();

        isRecording = false;
        isPaused = false;
        if (callback != null) callback.onStatusChanged("stopped", "user", null, null);

        return resultPath;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void pauseRecording() {
        if (!isRecording || isPaused) return;
        try {
            // Stop and release the current MediaRecorder so the segment file is
            // fully flushed to disk and can be played back / concatenated.
            finalizeActiveSegmentForPause();

            isPaused = true;
            pauseDurationMonitoring();

            if (callback != null) callback.onStatusChanged("paused", "user", null, null);
        } catch (Exception e) {
            Log.w(TAG, "Error pausing recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void resumeRecording() {
        if (!isRecording || !isPaused) return;
        try {
            // Drop any preview created for paused playback before we capture more audio.
            cleanupPausedPlaybackPreview();

            // Begin a brand-new segment for the next chunk of audio.
            currentSegmentFile = nextSegmentFile();

            mediaRecorder = new MediaRecorder();
            setBestAudioSource(mediaRecorder);
            configureRecorderCommon(mediaRecorder, currentSegmentFile.getAbsolutePath());

            try {
                mediaRecorder.prepare();
                mediaRecorder.start();
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException resuming MediaRecorder", se);
                if (callback != null) callback.onError("SecurityException: cannot resume recording");
                cleanupMediaRecorder();
                return;
            } catch (Exception ex) {
                Log.w(TAG, "Failed to start new segment on resume", ex);
                if (callback != null) callback.onError("Failed to resume recording: " + ex.getMessage());
                cleanupMediaRecorder();
                return;
            }

            isPaused = false;
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
            // Stop the active recorder so its segment file is closed before we delete it.
            try {
                cleanupMediaRecorder();
            } catch (Exception ignored) {}

            // Drop every captured segment and any preview file.
            cleanupSegments();
            cleanupPausedPlaybackPreview();
            segmentIndex = 0;

            // Fully stop monitoring so a subsequent resume restarts it from 0.
            stopDurationMonitoring();
            synchronized (this) {
                currentDuration = 0.0;
            }

            // Recording stays alive but paused; resumeRecording() will start a fresh segment.
            isPaused = true;

            if (callback != null) callback.onStatusChanged("paused", "user", null, null);
        } catch (Exception e) {
            Log.w(TAG, "Error resetting recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * Concatenate the segments captured so far into a temporary preview file
     * that callers can play back while the recording is still paused.
     *
     * @return the preview file (path/uri) ready to be handed to PlaybackManager.
     * @throws IOException if recording is not paused or no audio has been captured yet.
     */
    File prepareForPausedPlayback() throws IOException {
        if (!isRecording || !isPaused) {
            throw new IOException("playPausedRecording requires an active, paused recording");
        }
        if (segments.isEmpty()) {
            throw new IOException("No audio has been recorded yet to play back");
        }

        // Drop any prior preview before generating a new one — segments may have
        // grown (or shrunk via reset) since the last call.
        cleanupPausedPlaybackPreview();

        File previewDir = (segmentDirectory != null && segmentDirectory.getParentFile() != null)
                ? segmentDirectory.getParentFile()
                : context.getFilesDir();
        File preview = new File(previewDir, PREVIEW_FILE_PREFIX + System.currentTimeMillis() + ".m4a");

        AudioFileProcessor.concatenateAudioFiles(new ArrayList<>(segments), preview);
        previewFile = preview;
        return preview;
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

    private void finalizeActiveSegmentForPause() {
        if (mediaRecorder == null) {
            // No active recorder (e.g. after reset). Nothing to finalize, but the
            // current segment file may already represent prior audio.
            registerCurrentSegmentIfValid();
            return;
        }
        try {
            try {
                mediaRecorder.stop();
            } catch (IllegalStateException ise) {
                Log.w(TAG, "MediaRecorder stop in illegal state during pause", ise);
            } catch (RuntimeException re) {
                // stop() throws RuntimeException if no audio was captured yet
                // (e.g. pause invoked immediately after start).
                Log.w(TAG, "MediaRecorder stop runtime error during pause", re);
            }
            try { mediaRecorder.reset(); } catch (Exception ignored) {}
            try { mediaRecorder.release(); } catch (Exception ignored) {}
        } finally {
            mediaRecorder = null;
        }
        registerCurrentSegmentIfValid();
    }

    private void finalizeActiveSegmentForStop() {
        if (isPaused) {
            // The current segment was already finalized by pauseRecording.
            return;
        }
        finalizeActiveSegmentForPause();
    }

    private void registerCurrentSegmentIfValid() {
        if (currentSegmentFile == null) return;
        if (currentSegmentFile.exists() && currentSegmentFile.length() > 0) {
            segments.add(currentSegmentFile);
        } else {
            Log.w(TAG, "Discarding empty segment: " + currentSegmentFile.getAbsolutePath());
            try {
                if (currentSegmentFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    currentSegmentFile.delete();
                }
            } catch (Exception ignored) {}
        }
        currentSegmentFile = null;
    }

    private void cleanupSegments() {
        for (File segment : segments) {
            try {
                if (segment != null && segment.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    segment.delete();
                }
            } catch (Exception ignored) {}
        }
        segments.clear();

        // Also drop any in-flight segment file.
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
        // Make sure no stale file from a previous session remains.
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

    private void cleanupMediaRecorder() {
        try {
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (IllegalStateException ise) {
                    Log.w(TAG, "MediaRecorder stop called in illegal state", ise);
                } catch (Exception stopEx) {
                    Log.w(TAG, "MediaRecorder stop error", stopEx);
                }
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) {}
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {}
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanupMediaRecorder error", e);
        }
    }

    private void configureRecorderCommon(MediaRecorder recorder, String outputPath) {
        try {
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            int bitrate = AudioEngineConfig.Recording.DEFAULT_BITRATE;
            recorder.setAudioEncodingBitRate(bitrate);
            int sampleRate = AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
            recorder.setAudioSamplingRate(sampleRate);
            int channels = AudioEngineConfig.Recording.DEFAULT_CHANNELS;
            recorder.setAudioChannels(channels);
            recorder.setOutputFile(outputPath);
        } catch (Exception e) {
            Log.w(TAG, "configureRecorderCommon error", e);
        }
    }

    private void setBestAudioSource(MediaRecorder recorder) {
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        } catch (Exception e) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
            } catch (Exception inner1) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                } catch (Exception inner2) {
                    Log.w(TAG, "Failed to set audio source", inner2);
                }
            }
        }
    }

    // Duration monitoring methods
    private void startDurationMonitoring() {
        stopDurationMonitoring();
        Log.d(TAG, "Starting duration monitoring for recording");

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
        Log.d(TAG, "Duration monitoring stopped");
    }

    private void pauseDurationMonitoring() {
        isDurationPaused = true;
        Log.d(TAG, "Duration monitoring paused");
    }

    private void resumeDurationMonitoring() {
        if (!isDurationMonitoring) {
            Log.d(TAG, "Restarting duration monitoring after reset, preserving duration: " + currentDuration);
            startDurationMonitoring();
        } else {
            isDurationPaused = false;
        }
        Log.d(TAG, "Duration monitoring resumed");
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
            if (hasAudioFocus) {
                Log.d(TAG, "Audio focus granted for recording");
            } else {
                Log.w(TAG, "Audio focus denied for recording");
            }
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
            Log.d(TAG, "Audio focus abandoned");
        } catch (Exception e) {
            Log.e(TAG, "Error abandoning audio focus", e);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.d(TAG, "Audio focus changed: " + focusChange);

        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                Log.d(TAG, "Audio focus gained - recording can continue");
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                Log.w(TAG, "Audio focus lost permanently - another app is using audio");
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                Log.w(TAG, "Audio focus lost temporarily - transient interruption");
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                Log.d(TAG, "Audio focus loss can duck - continuing recording");
                break;

            default:
                Log.w(TAG, "Unknown audio focus change: " + focusChange);
                break;
        }
    }

    static class StartOptions {
        String path;
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
     * Pre-warm MediaRecorder for faster subsequent recordings
     */
    public void preWarmRecorder() {
        try {
            if (mediaRecorder == null && !isRecording) {
                Log.d(TAG, "Pre-warming MediaRecorder for faster subsequent recordings");
                mediaRecorder = new MediaRecorder();
                setBestAudioSource(mediaRecorder);
                Log.d(TAG, "MediaRecorder pre-warmed successfully (ready for configuration)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to pre-warm MediaRecorder", e);
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {}
                mediaRecorder = null;
            }
        }
    }

}
