package com.capacitor.audioengine;

import android.content.Context;
import android.media.MediaMuxer;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages segment rolling audio recording with automatic cleanup for Android
 * Features:
 * - Records audio in 5-minute segments using MediaRecorder (improved performance)
 * - Maintains a rolling buffer of the last 10 minutes (2 segments)
 * - Automatically removes oldest segments when buffer is full
 * - Merges segments into final audio file using MediaMuxer when recording stops
 * - Handles audio interruptions (phone calls, audio focus loss, etc.)
 */
public class SegmentRollingManager implements AudioInterruptionManager.InterruptionCallback {
    private static final String TAG = "SegmentRollingManager";

    // Segment rolling constants
    private static final int DEFAULT_SEGMENT_DURATION_MS = 30000; // 30 seconds (new default for faster finalize)
    private volatile int segmentDurationMs = DEFAULT_SEGMENT_DURATION_MS; // instance-configurable segment duration
    private static final int MAX_RETENTION_DURATION_MS = 600000; // 10 minutes
    private static final int MAX_SEGMENTS = MAX_RETENTION_DURATION_MS / DEFAULT_SEGMENT_DURATION_MS; // used only for default estimation

    /**
     * Set the per-segment duration (ms). Recommended range: 250 - 60000 ms.
     */
    public void setSegmentDurationMs(int ms) {
        int bounded = Math.max(250, Math.min(ms, 60000));
        if (bounded != ms) {
            Log.w(TAG, "Requested segmentDurationMs out of bounds, clamped to " + bounded + "ms");
        }
        this.segmentDurationMs = bounded;
        Log.d(TAG, "segmentDurationMs set to " + this.segmentDurationMs + "ms");
    }

    /**
     * Fast-trim an m4a file using MediaExtractor + MediaMuxer by copying samples within range.
     * Returns true if the fast path succeeded; false means caller should fall back to re-encoding/trimming.
     */
    private boolean fastTrimFile(File inputFile, File outputFile, double startSec, double endSec) {
        if (inputFile == null || !inputFile.exists()) return false;
        if (endSec <= startSec) return false;

        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(inputFile.getAbsolutePath());

            int trackCount = extractor.getTrackCount();
            int outTrack = -1;
            int sampleRate = -1;
            MediaFormat format = null;

            for (int i = 0; i < trackCount; i++) {
                MediaFormat mf = extractor.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    format = mf;
                    outTrack = i;
                    break;
                }
            }

            if (format == null) {
                Log.w(TAG, "fastTrimFile: no audio track found, falling back");
                return false;
            }

            // Create muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int writeTrackIndex = muxer.addTrack(format);
            muxer.start();

            // Seek to start
            long startUs = (long) (startSec * 1_000_000.0);
            long endUs = (long) (endSec * 1_000_000.0);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            ByteBuffer inputBuffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            while (true) {
                bufferInfo.offset = 0;
                bufferInfo.size = extractor.readSampleData(inputBuffer, 0);
                if (bufferInfo.size < 0) break;

                long sampleTime = extractor.getSampleTime();
                if (sampleTime < 0 || sampleTime > endUs) break;

                bufferInfo.presentationTimeUs = sampleTime - startUs; // rebase
                bufferInfo.flags = extractor.getSampleFlags();

                muxer.writeSampleData(writeTrackIndex, inputBuffer, bufferInfo);

                extractor.advance();
            }

            muxer.stop();
            muxer.release();
            extractor.release();

            return true;
        } catch (Exception e) {
            Log.w(TAG, "fastTrimFile failed, falling back: " + e.getMessage());
            try {
                extractor.release();
            } catch (Exception ignore) {}
            if (muxer != null) {
                try { muxer.release(); } catch (Exception ignore) {}
            }
            return false;
        }
    }

    // Callback interface for max duration events
    public interface MaxDurationCallback {
        void onMaxDurationReached();
    }

    // Callback interface for duration change events
    public interface DurationChangeCallback {
        void onDurationChanged(long durationMs);
    }

    // Recording components
    private MediaRecorder currentSegmentRecorder;
    // Continuous recording for 0s merge final file
    private MediaRecorder continuousRecorder;
    private File continuousOutputFile;
    private boolean isContinuousActive = false;
    private boolean enableContinuousRecording = true; // parity with iOS

    private Timer segmentTimer;
    private Timer durationUpdateTimer;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicInteger segmentCounter = new AtomicInteger(0);
    private final AtomicLong totalDuration = new AtomicLong(0);
    private long recordingStartTime = 0; // Track actual recording start time

    // Duration tracking state for interruptions
    private final AtomicBoolean isDurationPaused = new AtomicBoolean(false);
    private long pausedDurationOffset = 0; // Track time spent paused
    private long pauseStartTime = 0; // Track when current pause began

    // Duration tracking state for manual pause/resume operations
    private final AtomicBoolean isManuallyPaused = new AtomicBoolean(false);
    private long manualPausedDurationOffset = 0; // Track time spent manually paused
    private long manualPauseStartTime = 0; // Track when current manual pause began

    // Segment management
    private final ConcurrentLinkedQueue<File> segmentBuffer = new ConcurrentLinkedQueue<>();
    private File segmentsDirectory;
    private AudioRecordingConfig recordingConfig;
    private Long maxDurationMs; // Maximum recording duration in milliseconds
    private MaxDurationCallback maxDurationCallback;
    private DurationChangeCallback durationChangeCallback;

    // Memory optimization tracking
    private long lastMemoryCheckTime = 0;
    private static final long MEMORY_CHECK_INTERVAL_MS = 60000; // Check every minute
    private long totalSegmentBytes = 0; // Track total bytes in segment buffer

    // Error recovery configuration
    private static final int MAX_DELETION_RETRIES = 3;
    private static final long DELETION_RETRY_DELAY_MS = 500; // 500ms delay between retries

    // Configurable safety margins
    private double memorySafetyMargin = 1.3; // 30% safety margin by default
    private int segmentSafetyBuffer = 3; // Extra segments for safety by default (increased from 2 to 3)

    // Compression and recovery options
    private boolean enableSegmentCompression = false;
    private boolean enablePersistentIndexing = true;

    // Persistent indexing for crash recovery
    private File indexFile;
    private static final String INDEX_FILE_NAME = "segment_index.json";

    // Synchronization
    private final Object recordingLock = new Object();

    // Background pre-merge state
    private Thread preMergeThread;
    private final AtomicBoolean preMergeRunning = new AtomicBoolean(false);
    private final AtomicBoolean preMergeStopRequested = new AtomicBoolean(false);
    private final AtomicLong mergePlanVersion = new AtomicLong(0); // increment when segments change
    private final AtomicLong mergedVersion = new AtomicLong(0); // last version applied to merged_temp
    private final Object preMergeLock = new Object();
    private File mergedTempFile;
    // Incremental pre-merge tracking
    private final ArrayList<String> preMergedNames = new ArrayList<>();
    private long preMergedDurationUs = 0;

    // Background continuous window (trim) worker
    private Thread continuousWindowThread;
    private final AtomicBoolean continuousWindowRunning = new AtomicBoolean(false);
    private final AtomicBoolean continuousWindowStopRequested = new AtomicBoolean(false);
    private final Object continuousWindowLock = new Object();
    private File continuousWindowTempFile;

    // Audio interruption handling
    private final Context context;
    private AudioInterruptionManager interruptionManager;
    private boolean wasInterruptedByCall = false;
    private AudioInterruptionManager.InterruptionType lastInterruptionType = null;

    /**
     * Initialize the segment rolling manager
     * @param context Application context for audio interruption handling
     * @param baseDirectory Base directory where segments will be stored
     */
    public SegmentRollingManager(Context context, File baseDirectory) throws IOException {
        this.context = context.getApplicationContext();
        // Create segments directory
        segmentsDirectory = new File(baseDirectory, "AudioSegments");
        if (!segmentsDirectory.exists() && !segmentsDirectory.mkdirs()) {
            throw new IOException("Failed to create segments directory");
        }

        // Initialize persistent indexing
        indexFile = new File(segmentsDirectory, INDEX_FILE_NAME);

        // Clean up any old segments from previous sessions
        cleanupOldSegments();

        // Prepare merged temp file path
        mergedTempFile = new File(segmentsDirectory, ".merged_temp.m4a");
        // Ensure no stale temp file from previous runs
        if (mergedTempFile.exists()) {
            boolean deleted = mergedTempFile.delete();
            if (!deleted) {
                Log.w(TAG, "Failed to delete stale merged temp file at init: " + mergedTempFile.getAbsolutePath());
            }
        }

        // Recover from persistent index if available
        if (enablePersistentIndexing) {
            recoverFromPersistentIndex();
        }

        // Initialize audio interruption manager
        interruptionManager = new AudioInterruptionManager(context);
        interruptionManager.setInterruptionCallback(this);

        Log.d(TAG, "SegmentRollingManager initialized with error recovery, persistent indexing, and audio interruption handling");
    }

    /**
     * Start segment rolling recording with audio interruption handling
     * @param config Recording configuration
     */
    public void startSegmentRolling(AudioRecordingConfig config) throws IOException {
        synchronized (recordingLock) {
            if (isActive.get()) {
                throw new IllegalStateException("Segment rolling already active");
            }

            // Start audio interruption monitoring
            if (interruptionManager != null) {
                interruptionManager.startMonitoring();
                Log.d(TAG, "Audio interruption monitoring started");
            }

            this.recordingConfig = config;

            // Reset state
            segmentCounter.set(0);
            totalDuration.set(0);
            segmentBuffer.clear();
            isActive.set(true);
            recordingStartTime = System.currentTimeMillis(); // Track start time

            // Reset pause tracking variables
            isDurationPaused.set(false);
            pausedDurationOffset = 0;
            pauseStartTime = 0;
            isManuallyPaused.set(false);
            manualPausedDurationOffset = 0;
            manualPauseStartTime = 0;

            // Start continuous full-session recorder for 0s merge (optional)
            if (enableContinuousRecording) {
                startContinuousRecorder();
            } else {
                Log.d(TAG, "Continuous recorder disabled via config flag");
            }

            // Start first rolling segment
            startNewSegment();

            // Start background pre-merge worker
            startPreMergeWorker();

            // Schedule segment rotation timer
            segmentTimer = new Timer("SegmentRotationTimer", true);
            segmentTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    rotateSegment();
                }
            }, segmentDurationMs, segmentDurationMs);

            // Schedule duration update timer for client-side duration updates
            durationUpdateTimer = new Timer("DurationUpdateTimer", true);
            durationUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    // Emit duration change event every second for real-time updates
                    if (durationChangeCallback != null && isActive.get()) {
                        long currentDuration = getElapsedRecordingTime();
                        try {
                            durationChangeCallback.onDurationChanged(currentDuration);
                        } catch (Exception e) {
                            Log.w(TAG, "Error emitting periodic duration change event", e);
                        }
                    }
                }
            }, 1000, 1000); // Start after 1 second, then every 1 second

            Log.d(TAG, "Started segment rolling recording");
        }
    }

    /**
     * Pause segment rolling recording
     */
    public void pauseSegmentRolling() {
        synchronized (recordingLock) {
            if (!isActive.get()) {
                return;
            }

            // Start tracking manual pause time
            if (!isManuallyPaused.get()) {
                isManuallyPaused.set(true);
                manualPauseStartTime = System.currentTimeMillis();
                Log.d(TAG, "Started tracking manual pause at: " + manualPauseStartTime);
            }

            // Pause current segment recording
            if (currentSegmentRecorder != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    currentSegmentRecorder.pause();
                } catch (Exception e) {
                    Log.w(TAG, "Error pausing segment recording", e);
                }
            }

            // Pause continuous recorder
            if (continuousRecorder != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    continuousRecorder.pause();
                } catch (Exception e) {
                    Log.w(TAG, "Error pausing continuous recording", e);
                }
            }

            // Cancel segment timer
            if (segmentTimer != null) {
                segmentTimer.cancel();
                segmentTimer = null;
            }

            // Cancel duration update timer
            if (durationUpdateTimer != null) {
                durationUpdateTimer.cancel();
                durationUpdateTimer = null;
            }

            Log.d(TAG, "Paused segment rolling recording");
        }
    }

    /**
     * Resume segment rolling recording
     */
    public void resumeSegmentRolling() throws IOException {
        synchronized (recordingLock) {
            if (!isActive.get()) {
                throw new IllegalStateException("No segment rolling to resume");
            }

            // End tracking manual pause time
            if (isManuallyPaused.get() && manualPauseStartTime > 0) {
                long pauseDuration = System.currentTimeMillis() - manualPauseStartTime;
                manualPausedDurationOffset += pauseDuration;
                isManuallyPaused.set(false);
                manualPauseStartTime = 0;
                Log.d(TAG, "Ended manual pause tracking. Pause duration: " + pauseDuration + "ms, Total manual pause offset: " + manualPausedDurationOffset + "ms");
            }

            // Resume current segment or start new one if needed
            if (currentSegmentRecorder != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    currentSegmentRecorder.resume();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resume current segment, starting new one", e);
                    startNewSegment();
                }
            } else {
                startNewSegment();
            }

            // Resume or restart continuous recorder if needed
            if (continuousRecorder == null) {
                try {
                    startContinuousRecorder();
                    Log.d(TAG, "Continuous recorder started after reset");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start continuous recorder after reset", e);
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                try {
                    continuousRecorder.resume();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resume continuous recorder", e);
                }
            }

            // Restart segment timer - calculate remaining time to next rotation
            long elapsedInCurrentSegment = (System.currentTimeMillis() - recordingStartTime - pausedDurationOffset - manualPausedDurationOffset) % segmentDurationMs;
            long timeToNextRotation = segmentDurationMs - elapsedInCurrentSegment;

            segmentTimer = new Timer("SegmentRotationTimer", true);
            segmentTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    rotateSegment();
                }
            }, timeToNextRotation, segmentDurationMs);

            // Restart duration update timer
            durationUpdateTimer = new Timer("DurationUpdateTimer", true);
            durationUpdateTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    // Emit duration change event every second for real-time updates
                    if (durationChangeCallback != null && isActive.get()) {
                        long currentDuration = getElapsedRecordingTime();
                        try {
                            durationChangeCallback.onDurationChanged(currentDuration);
                        } catch (Exception e) {
                            Log.w(TAG, "Error emitting periodic duration change event", e);
                        }
                    }
                }
            }, 1000, 1000); // Start after 1 second, then every 1 second

            Log.d(TAG, "Resumed segment rolling recording");
        }
    }

    /**
     * Reset segment rolling to clear all segments and reset duration counters
     * Keeps the recording session ready but stops active recording
     */
    public void resetSegmentRolling() throws IOException {
        synchronized (recordingLock) {
            if (!isActive.get()) {
                throw new IllegalStateException("No segment rolling to reset");
            }

            Log.d(TAG, "Resetting segment rolling - clearing segments and duration");

            // Stop timers
            if (segmentTimer != null) {
                segmentTimer.cancel();
                segmentTimer = null;
            }

            if (durationUpdateTimer != null) {
                durationUpdateTimer.cancel();
                durationUpdateTimer = null;
            }

            // Stop current segment if recording
            if (currentSegmentRecorder != null) {
                try {
                    currentSegmentRecorder.stop();
                    currentSegmentRecorder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping current segment during reset", e);
                } finally {
                    currentSegmentRecorder = null;
                }
            }

            // Clear all segments and reset buffers
            cleanupSegments();

            // Also discard any continuous recording so post-reset audio starts fresh
            if (continuousRecorder != null) {
                try {
                    if (isContinuousActive) {
                        // If paused, resume briefly to avoid stop errors
                        if (isManuallyPaused.get() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                            try { continuousRecorder.resume(); } catch (Exception ignored) {}
                        }
                        continuousRecorder.stop();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping continuous recorder during reset", e);
                } finally {
                    try { continuousRecorder.release(); } catch (Exception ignored) {}
                    continuousRecorder = null;
                    isContinuousActive = false;
                }
            }
            // Delete previous continuous output file if exists
            if (continuousOutputFile != null && continuousOutputFile.exists()) {
                boolean deleted = continuousOutputFile.delete();
                if (!deleted) {
                    Log.w(TAG, "Failed to delete previous continuous file during reset: " + continuousOutputFile.getAbsolutePath());
                }
            }
            continuousOutputFile = null;

            // Reset all duration tracking variables to start fresh
            recordingStartTime = System.currentTimeMillis(); // Reset start time to now
            pausedDurationOffset = 0;
            pauseStartTime = 0;
            manualPausedDurationOffset = 0;
            manualPauseStartTime = recordingStartTime; // Start manual pause at reset time to keep duration at 0 while paused

            // Reset segment counter
            segmentCounter.set(0);
            totalDuration.set(0);

            // Reset pause states
            isDurationPaused.set(false);
            isManuallyPaused.set(true); // Mark as paused after reset so duration stays 0 until resume

            // Keep session active without creating a new recorder
            // This maintains the session state while avoiding MediaRecorder stop issues
            // The session can be resumed later or stopped cleanly
            Log.d(TAG, "Session remains active but without active recorder - ready for resume or stop");

            Log.d(TAG, "Segment rolling reset completed - session active and ready");
        }
    }

    /**
     * Stop segment rolling and return the merged file
     */
    public File stopSegmentRolling() throws IOException {
        synchronized (recordingLock) {
            long __stopEnterNs = System.nanoTime();
            Log.d(TAG, "stopSegmentRolling called - isActive: " + isActive.get());

            if (!isActive.get()) {
                Log.e(TAG, "Cannot stop segment rolling - already inactive");
                throw new IllegalStateException("No segment rolling to stop");
            }

            // Cancel timer
            if (segmentTimer != null) {
                segmentTimer.cancel();
                segmentTimer = null;
            }

            // Cancel duration update timer
            if (durationUpdateTimer != null) {
                durationUpdateTimer.cancel();
                durationUpdateTimer = null;
            }

            // Stop current segment recording (if any exists)
            if (currentSegmentRecorder != null) {
                try {
                    // If recording is manually paused, we need to handle it carefully
                    if (isManuallyPaused.get() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        Log.d(TAG, "MediaRecorder is paused, resuming before stop to avoid error -1007");
                        try {
                            currentSegmentRecorder.resume();
                            // Give it a moment to resume
                            Thread.sleep(50);
                        } catch (Exception resumeException) {
                            Log.w(TAG, "Failed to resume paused recorder, will try to stop anyway", resumeException);
                        }
                    }

                    currentSegmentRecorder.stop();
                    currentSegmentRecorder.release();

                    // Add current segment to buffer if it has content
                    File lastSegmentFile = getSegmentFile(segmentCounter.get());
                    if (lastSegmentFile.exists() && lastSegmentFile.length() > 1024) { // Only add if has content
                        addSegmentToBuffer(lastSegmentFile);
                    } else {
                        Log.d(TAG, "Last segment file has no content or doesn't exist, skipping buffer addition");
                    }

                } catch (Exception e) {
                    Log.w(TAG, "Error stopping current segment: " + e.getMessage(), e);
                    // Don't let this block the rest of the cleanup process
                } finally {
                    currentSegmentRecorder = null;
                }
            } else {
                Log.d(TAG, "No current segment recorder to stop (this is normal after reset)");
            }

            // Timing: after finalizing last segment
            long __afterFinalizeNs = System.nanoTime();
            long __deltaFinalizeMs = (__afterFinalizeNs - __stopEnterNs) / 1_000_000L;
            Log.d(TAG, "T_stop: after finalizing last segment: " + __deltaFinalizeMs + " ms since enter");
            if (__deltaFinalizeMs > 300) {
                Log.w(TAG, "Slow step: finalize last segment took >300ms (" + __deltaFinalizeMs + "ms)");
            }

            // Give pre-merge worker a final tick to incorporate the just-added segment
            try {
                requestPreMerge(); // ensure worker knows plan changed
            } catch (Throwable ignored) {}
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}

            // Stop pre-merge worker and try to use pre-merged file for instant finalize
            stopPreMergeWorker();
            long __afterStopWorkerNs = System.nanoTime();
            long __deltaStopWorkerMs = (__afterStopWorkerNs - __stopEnterNs) / 1_000_000L;
            Log.d(TAG, "T_stop: after stopping pre-merge worker: " + __deltaStopWorkerMs + " ms since enter");
            if (__deltaStopWorkerMs > 300) {
                Log.w(TAG, "Slow step: stop pre-merge worker took >300ms (" + __deltaStopWorkerMs + "ms)");
            }

            File mergedFile = null;
            try {
                performPreMergeIfNeeded();
            } catch (Exception e) {
                Log.w(TAG, "Final pre-merge attempt failed, will try other paths", e);
            }
            long __afterFinalPreMergeNs = System.nanoTime();
            long __deltaFinalPreMergeMs = (__afterFinalPreMergeNs - __stopEnterNs) / 1_000_000L;
            Log.d(TAG, "T_stop: after final performPreMergeIfNeeded(): " + __deltaFinalPreMergeMs + " ms since enter");
            if (__deltaFinalPreMergeMs > 300) {
                Log.w(TAG, "Slow step: final pre-merge took >300ms (" + __deltaFinalPreMergeMs + "ms)");
            }
            if (mergedTempFile != null && mergedTempFile.exists() && mergedTempFile.length() > 0
                    && mergedVersion.get() == mergePlanVersion.get()) {
                // Rename temp to final
                File finalFile = new File(segmentsDirectory, "final_output_" + System.currentTimeMillis() + ".m4a");
                boolean ok = mergedTempFile.renameTo(finalFile);
                if (ok) {
                    mergedFile = finalFile;
                    long __afterRenameNs = System.nanoTime();
                    long __deltaRenameMs = (__afterRenameNs - __stopEnterNs) / 1_000_000L;
                    Log.d(TAG, "T_stop: after fast-rename finalize: " + __deltaRenameMs + " ms since enter");
                    if (__deltaRenameMs > 300) {
                        Log.w(TAG, "Slow step: rename finalize took >300ms (" + __deltaRenameMs + "ms)");
                    }
                    Log.d(TAG, "Fast finalize using pre-merged file: " + mergedFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Failed to rename merged temp to final; will try continuous or merge fallback");
                }
            }

            if (mergedFile == null) {
                // First, stop the continuous recorder to obtain final continuous .m4a instantly (0s merge)
                mergedFile = stopContinuousRecorderAndReturnFile();
                long __afterContinuousNs = System.nanoTime();
                long __deltaContinuousMs = (__afterContinuousNs - __stopEnterNs) / 1_000_000L;
                Log.d(TAG, "T_stop: after returning continuous file: " + __deltaContinuousMs + " ms since enter");
                if (__deltaContinuousMs > 300) {
                    Log.w(TAG, "Slow step: returning continuous file took >300ms (" + __deltaContinuousMs + "ms)");
                }
            }
            if (mergedFile != null) {
                Log.d(TAG, "Continuous recording file ready: " + mergedFile.getAbsolutePath());

                // If a max rolling duration is set, prefer background-trimmed window for O(1) stop
                if (maxDurationMs != null && maxDurationMs > 0) {
                    boolean usedBackgroundWindow = false;
                    try {
                        if (continuousWindowTempFile != null && continuousWindowTempFile.exists() && continuousWindowTempFile.length() > 1024) {
                            File trimmed = new File(mergedFile.getParent(),
                                    "trimmed_continuous_" + System.currentTimeMillis() + ".m4a");
                            boolean ok = continuousWindowTempFile.renameTo(trimmed);
                            if (ok) {
                                mergedFile = trimmed;
                                usedBackgroundWindow = true;
                                Log.d(TAG, "Used background-trimmed continuous window file: " + mergedFile.getAbsolutePath());
                            } else {
                                Log.w(TAG, "Failed to rename background window file; will try inline fast trim");
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error using background-trimmed window file", e);
                    }
                    if (!usedBackgroundWindow) {
                        try {
                            long actualDurationMs = getAudioFileDuration(mergedFile);
                            double actualDurationSec = actualDurationMs / 1000.0;
                            double targetDurationSec = maxDurationMs / 1000.0;

                            if (actualDurationSec > targetDurationSec) {
                                double startTrimSec = Math.max(0.0, actualDurationSec - targetDurationSec);
                                File trimmed = new File(mergedFile.getParent(),
                                        "trimmed_continuous_" + System.currentTimeMillis() + ".m4a");

                                // Precision trim to keep the last maxDuration window
                                boolean fastOk = fastTrimFile(mergedFile, trimmed, startTrimSec, actualDurationSec);
                                if (!fastOk) {
                                    AudioFileProcessor.trimAudioFile(mergedFile, trimmed, startTrimSec, actualDurationSec);
                                }

                                // Replace original with trimmed result
                                boolean deleted = mergedFile.delete();
                                if (!deleted) {
                                    Log.w(TAG, "Failed to delete original continuous file after trimming: " + mergedFile.getAbsolutePath());
                                }
                                mergedFile = trimmed;
                                Log.d(TAG, "Continuous file trimmed to last " + String.format("%.3f", targetDurationSec) + "s: " + mergedFile.getAbsolutePath());
                            } else {
                                Log.d(TAG, "Continuous duration <= maxDuration; no trim needed (" + String.format("%.3f", actualDurationSec) + "s <= " + String.format("%.3f", targetDurationSec) + "s)");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to trim continuous file to rolling window; returning untrimmed continuous file", e);
                        }
                    }
                }
            } else {
                // Fallback: If continuous recording failed or produced invalid file, merge segments
                int segmentCountBeforeMerge = segmentBuffer.size();
                Log.d(TAG, "Falling back to merge. Segments in buffer: " + segmentCountBeforeMerge);
                if (segmentCountBeforeMerge == 0) {
                    Log.w(TAG, "No segments to merge - creating minimal empty file");
                    mergedFile = createMinimalEmptyAudioFile();
                } else {
                    mergedFile = mergeSegments();
                }
                Log.d(TAG, "Merge completed. Output file: " + mergedFile.getName() +
                        " (size: " + mergedFile.length() + " bytes)");
            }

            // Cleanup segments AFTER finishing
            isActive.set(false);

            // Reset pause tracking state
            isDurationPaused.set(false);
            pausedDurationOffset = 0;
            pauseStartTime = 0;
            isManuallyPaused.set(false);
            manualPausedDurationOffset = 0;
            manualPauseStartTime = 0;

            cleanupSegments();

            Log.d(TAG, "Stopped segment rolling and merged " + segmentBuffer.size() + " segments");

            return mergedFile;
        }
    }    /**
     * Get current recording duration across all segments in the rolling window
     * For rolling recording, this returns the actual elapsed time since recording started,
     * accounting for interruption pauses and manual pauses to provide accurate usable recording time
     */
    public long getElapsedRecordingTime() {
        if (!isActive.get()) {
            return 0;
        }

        // Calculate actual elapsed time since recording started
        long elapsedTime = System.currentTimeMillis() - recordingStartTime;

        // Subtract time spent paused due to interruptions for accurate duration
        long adjustedElapsedTime = elapsedTime - pausedDurationOffset;

        // Subtract time spent manually paused for accurate duration
        adjustedElapsedTime -= manualPausedDurationOffset;

        // If currently manually paused, subtract the current pause duration
        if (isManuallyPaused.get() && manualPauseStartTime > 0) {
            long currentPauseDuration = System.currentTimeMillis() - manualPauseStartTime;
            adjustedElapsedTime -= currentPauseDuration;
        }

        // Return the actual recording time (excluding interruption pauses and manual pauses)
        return Math.max(0, adjustedElapsedTime);
    }

    /**
     * Get the duration of an audio file in milliseconds using robust fallbacks.
     * Order of attempts:
     * 1) MediaMetadataRetriever (fast, common)
     * 2) MediaPlayer (reliable for many formats)
     * 3) MediaExtractor track format duration (microseconds -> ms)
     * 4) WAV/RIFF PCM header parsing (no metadata case)
     */
    private long getAudioFileDuration(File audioFile) throws IOException {
        // 1) Try MediaMetadataRetriever
        android.media.MediaMetadataRetriever retriever = null;
        try {
            retriever = new android.media.MediaMetadataRetriever();
            retriever.setDataSource(audioFile.getAbsolutePath());
            String durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long dur = Long.parseLong(durationStr);
                if (dur > 0) return dur; // Already in ms
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaMetadataRetriever could not get duration: " + e.getMessage());
        } finally {
            if (retriever != null) {
                try { retriever.release(); } catch (Exception e) { Log.w(TAG, "Error releasing MediaMetadataRetriever", e); }
            }
        }

        // 2) Try MediaPlayer
        android.media.MediaPlayer mp = null;
        try {
            mp = new android.media.MediaPlayer();
            mp.setDataSource(audioFile.getAbsolutePath());
            mp.setOnErrorListener((player, what, extra) -> false);
            mp.prepare();
            int dur = mp.getDuration();
            if (dur > 0) return (long) dur; // ms
        } catch (Exception e) {
            Log.w(TAG, "MediaPlayer could not get duration: " + e.getMessage());
        } finally {
            if (mp != null) {
                try { mp.release(); } catch (Exception ignored) {}
            }
        }

        // 3) Try MediaExtractor
        android.media.MediaExtractor extractor = null;
        try {
            extractor = new android.media.MediaExtractor();
            extractor.setDataSource(audioFile.getAbsolutePath());
            int tracks = extractor.getTrackCount();
            for (int i = 0; i < tracks; i++) {
                android.media.MediaFormat fmt = extractor.getTrackFormat(i);
                if (fmt != null && fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    long durUs = fmt.getLong(android.media.MediaFormat.KEY_DURATION);
                    if (durUs > 0) {
                        return durUs / 1000L; // us -> ms
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MediaExtractor could not get duration: " + e.getMessage());
        } finally {
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
            }
        }

        // 4) Try direct WAV/RIFF parsing as a last resort
        Long wavDur = tryParseWavDuration(audioFile);
        if (wavDur != null && wavDur > 0) {
            return wavDur;
        }

        throw new IOException("Failed to get audio duration: Could not extract duration by any method");
    }

    /**
     * Attempt to parse duration from a WAV/RIFF PCM file by reading its header.
     * Returns duration in milliseconds, or null if not a supported WAV or parsing fails.
     */
    private Long tryParseWavDuration(File audioFile) {
        java.io.RandomAccessFile raf = null;
        try {
            raf = new java.io.RandomAccessFile(audioFile, "r");
            if (raf.length() < 44) {
                return null;
            }

            // Read RIFF header
            byte[] header = new byte[12];
            raf.readFully(header);
            if (!bytesEqual(header, 0, new byte[]{'R','I','F','F'}) || !bytesEqual(header, 8, new byte[]{'W','A','V','E'})) {
                return null; // Not RIFF/WAVE
            }

            int channels = 0;
            int sampleRate = 0;
            int byteRate = 0;
            int bitsPerSample = 0;
            long dataSize = 0;
            boolean fmtFound = false;
            boolean dataFound = false;

            while (raf.getFilePointer() + 8 <= raf.length()) { // need at least id(4)+size(4)
                byte[] chunkId = new byte[4];
                raf.readFully(chunkId);
                int chunkSize = readLeInt(raf);

                String id = new String(chunkId, java.nio.charset.StandardCharsets.US_ASCII);

                if ("fmt ".equals(id)) {
                    // PCM fmt chunk has at least 16 bytes
                    int audioFormat = readLeShort(raf);
                    channels = readLeShort(raf);
                    sampleRate = readLeInt(raf);
                    byteRate = readLeInt(raf);
                    int blockAlign = readLeShort(raf);
                    bitsPerSample = readLeShort(raf);

                    int remaining = chunkSize - 16;
                    if (remaining > 0) {
                        raf.seek(raf.getFilePointer() + remaining);
                    }
                    fmtFound = true;
                } else if ("data".equals(id)) {
                    dataSize = (chunkSize & 0xFFFFFFFFL);
                    // Move file pointer past data without reading
                    raf.seek(raf.getFilePointer() + dataSize);
                    dataFound = true;
                } else {
                    // Skip other chunks
                    raf.seek(raf.getFilePointer() + (chunkSize & 0xFFFFFFFFL));
                }

                if (fmtFound && dataFound) break;
            }

            if (byteRate > 0 && dataSize > 0) {
                long durationMs = (dataSize * 1000L) / (long) byteRate;
                if (durationMs > 0) return durationMs;
            } else if (sampleRate > 0 && channels > 0 && bitsPerSample > 0 && dataSize > 0) {
                long bytesPerSec = (long) sampleRate * (long) channels * (long) bitsPerSample / 8L;
                if (bytesPerSec > 0) {
                    long durationMs = (dataSize * 1000L) / bytesPerSec;
                    if (durationMs > 0) return durationMs;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WAV parser could not get duration: " + e.getMessage());
        } finally {
            if (raf != null) {
                try { raf.close(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    // Helpers for little-endian reading
    private static int readLeInt(java.io.RandomAccessFile raf) throws java.io.IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        int b3 = raf.read();
        int b4 = raf.read();
        if ((b1 | b2 | b3 | b4) < 0) throw new java.io.EOFException();
        return (b1 & 0xFF) | ((b2 & 0xFF) << 8) | ((b3 & 0xFF) << 16) | ((b4 & 0xFF) << 24);
    }

    private static int readLeShort(java.io.RandomAccessFile raf) throws java.io.IOException {
        int b1 = raf.read();
        int b2 = raf.read();
        if ((b1 | b2) < 0) throw new java.io.EOFException();
        return (b1 & 0xFF) | ((b2 & 0xFF) << 8);
    }

    private static boolean bytesEqual(byte[] arr, int offset, byte[] match) {
        if (offset + match.length > arr.length) return false;
        for (int i = 0; i < match.length; i++) {
            if (arr[offset + i] != match[i]) return false;
        }
        return true;
    }

    /**
     * Check if segment rolling is currently active
     */
    public boolean isSegmentRollingActive() {
        return isActive.get();
    }

    /**
     * Enable or disable using the continuous full-session recorder optimization (parity with iOS)
     */
    public void setContinuousRecordingEnabled(boolean enabled) {
        this.enableContinuousRecording = enabled;
    }

    /**
     * Get the duration of audio currently buffered (available for processing)
     * This represents the actual audio that will be included in the final recording
     */
    public long getBufferedAudioDuration() {
        if (!isActive.get()) {
            return getElapsedRecordingTime();
        }
        // segments count * segmentDuration + current segment elapsed
        int segmentsInBuffer = segmentBuffer.size();
        long segmentsDurationMs = (long) segmentsInBuffer * (long) segmentDurationMs;
        // Estimate current segment duration as time since last rotation within the segment window
        long now = System.currentTimeMillis();
        long currentSegmentElapsed = (now - recordingStartTime) % (long) segmentDurationMs;
        // If manually paused, the current segment is not growing; set elapsed to 0
        if (isManuallyPaused.get()) {
            currentSegmentElapsed = 0;
        }
        return segmentsDurationMs + Math.max(0, currentSegmentElapsed);
    }

    /**
     * Check if recording is currently paused (either manually or due to interruption)
     */
    public boolean isRecordingPaused() {
        return isManuallyPaused.get() || isDurationPaused.get();
    }

    /**
     * Check if recording has reached max duration and should be stopped
     */
    public boolean hasReachedMaxDuration() {
        if (!isActive.get() || maxDurationMs == null || maxDurationMs <= 0) {
            return false;
        }

        long elapsedTime = System.currentTimeMillis() - recordingStartTime;
        return elapsedTime >= maxDurationMs;
    }

    /**
     * Set maximum recording duration in milliseconds
     */
    public void setMaxDuration(long maxDurationMs) {
        // Enhanced validation for edge cases
        if (maxDurationMs <= 0) {
            Log.w(TAG, "Invalid maxDuration: " + maxDurationMs + "ms, ignoring");
            this.maxDurationMs = null;
            return;
        }

        // Warn if maxDuration is very small (less than 2 segments)
    if (maxDurationMs < segmentDurationMs * 2) {
            Log.w(TAG, "Very small maxDuration: " + (maxDurationMs / 1000) + "s (less than 2 segments). " +
                  "Minimum recommended duration is " + (segmentDurationMs * 2 / 1000) + "s for optimal rolling buffer performance.");
        }

        // Warn if maxDuration is extremely large (more than 2 hours)
        if (maxDurationMs > 7200000) { // 2 hours
            Log.w(TAG, "Very large maxDuration: " + (maxDurationMs / 1000) + "s. " +
                  "This may result in high memory usage. Consider using linear recording for very long durations.");
        }

        this.maxDurationMs = maxDurationMs;
        Log.d(TAG, "Set max duration to " + (maxDurationMs / 1000) + " seconds (" +
              Math.ceil((double) maxDurationMs / segmentDurationMs) + " segments)");
    }

    /**
     * Set callback for max duration reached events
     */
    public void setMaxDurationCallback(MaxDurationCallback callback) {
        this.maxDurationCallback = callback;
    }

    /**
     * Set callback for duration change events
     */
    public void setDurationChangeCallback(DurationChangeCallback callback) {
        this.durationChangeCallback = callback;
    }

    /**
     * Configure safety margins for memory and segment calculations
     * @param memorySafetyMargin Multiplier for memory safety margin (e.g., 1.3 = 30% extra)
     * @param segmentSafetyBuffer Number of extra segments to keep for safety
     */
    public void configureSafetyMargins(double memorySafetyMargin, int segmentSafetyBuffer) {
        if (memorySafetyMargin < 1.0) {
            Log.w(TAG, "Memory safety margin should be >= 1.0, using 1.0");
            this.memorySafetyMargin = 1.0;
        } else {
            this.memorySafetyMargin = memorySafetyMargin;
        }

        if (segmentSafetyBuffer < 0) {
            Log.w(TAG, "Segment safety buffer should be >= 0, using 0");
            this.segmentSafetyBuffer = 0;
        } else {
            this.segmentSafetyBuffer = segmentSafetyBuffer;
        }

        Log.d(TAG, "Configured safety margins: memory=" + this.memorySafetyMargin +
              ", segments=" + this.segmentSafetyBuffer);
    }

    /**
     * Enable or disable segment compression before deletion
     * @param enabled Whether to compress segments before deletion
     */
    public void setSegmentCompressionEnabled(boolean enabled) {
        this.enableSegmentCompression = enabled;
        Log.d(TAG, "Segment compression " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Enable or disable persistent segment indexing for crash recovery
     * @param enabled Whether to enable persistent indexing
     */
    public void setPersistentIndexingEnabled(boolean enabled) {
        this.enablePersistentIndexing = enabled;
        Log.d(TAG, "Persistent indexing " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Start the continuous full-session recorder to enable 0s merge at stop
     */
    private void startContinuousRecorder() throws IOException {
        // If already active, do nothing
        if (isContinuousActive && continuousRecorder != null) {
            return;
        }

        // Build output file in parent directory of segments
        File parentDir = segmentsDirectory != null ? segmentsDirectory.getParentFile() : null;
        if (parentDir == null) {
            parentDir = segmentsDirectory; // fallback, should not be null normally
        }
        if (parentDir != null && !parentDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parentDir.mkdirs();
        }

        continuousOutputFile = new File(parentDir,
                "continuous_" + System.currentTimeMillis() + ".m4a");

        // Release previous if any
        if (continuousRecorder != null) {
            try { continuousRecorder.release(); } catch (Exception ignored) {}
        }
        continuousRecorder = new MediaRecorder();

        try {
            continuousRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            continuousRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            continuousRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            continuousRecorder.setOutputFile(continuousOutputFile.getAbsolutePath());
            continuousRecorder.setAudioSamplingRate(recordingConfig.getSampleRate());
            continuousRecorder.setAudioChannels(recordingConfig.getChannels());
            continuousRecorder.setAudioEncodingBitRate(recordingConfig.getBitrate());

            continuousRecorder.prepare();
            continuousRecorder.start();
            isContinuousActive = true;
            // Prepare background continuous window temp file in same directory
            try {
                File cwParentDir = continuousOutputFile.getParentFile();
                if (cwParentDir != null) {
                    continuousWindowTempFile = new File(cwParentDir, ".continuous_window_temp.m4a");
                    if (continuousWindowTempFile.exists()) {
                        // best-effort cleanup
                        try { continuousWindowTempFile.delete(); } catch (Exception ignore) {}
                    }
                }
            } catch (Exception ignore) {}
            // Start background window worker if we have a rolling window configured
            if (maxDurationMs != null && maxDurationMs > 0) {
                startContinuousWindowWorker();
            }
            Log.d(TAG, "Started continuous recorder: " + continuousOutputFile.getAbsolutePath());
        } catch (Exception e) {
            isContinuousActive = false;
            try { continuousRecorder.release(); } catch (Exception ignored) {}
            continuousRecorder = null;
            // Propagate as IO to allow caller to decide fallback (merge)
            throw new IOException("Failed to start continuous recorder", e);
        }
    }

    /**
     * Stop the continuous recorder and return its file if valid
     */
    private File stopContinuousRecorderAndReturnFile() {
        File out = continuousOutputFile;
        if (continuousRecorder != null) {
            // Stop window worker first to avoid trimming during stop
            stopContinuousWindowWorker();
            try {
                if (isContinuousActive) {
                    // If paused, resume briefly to avoid stop errors
                    if (isManuallyPaused.get() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        try { continuousRecorder.resume(); } catch (Exception ignored) {}
                    }
                    continuousRecorder.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping continuous recorder", e);
            } finally {
                try { continuousRecorder.release(); } catch (Exception ignored) {}
                continuousRecorder = null;
                isContinuousActive = false;
            }
        }
        return (out != null && out.exists() && out.length() > 1024) ? out : null;
    }

    /**
     * Start recording a new segment
     */
    private void startNewSegment() throws IOException {
        File segmentFile = getSegmentFile(segmentCounter.get());

        // Release previous recorder if exists
        if (currentSegmentRecorder != null) {
            try {
                currentSegmentRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing previous segment recorder", e);
            }
        }

        // Create new MediaRecorder for this segment
        currentSegmentRecorder = new MediaRecorder();

        try {
            // Configure MediaRecorder with same settings as main recording
            currentSegmentRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            currentSegmentRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            currentSegmentRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            currentSegmentRecorder.setOutputFile(segmentFile.getAbsolutePath());
            currentSegmentRecorder.setAudioSamplingRate(recordingConfig.getSampleRate());
            currentSegmentRecorder.setAudioChannels(recordingConfig.getChannels());
            currentSegmentRecorder.setAudioEncodingBitRate(recordingConfig.getBitrate());

            // Prepare and start recording
            currentSegmentRecorder.prepare();
            currentSegmentRecorder.start();

            Log.d(TAG, "Started segment " + segmentCounter.get());

        } catch (Exception e) {
            if (currentSegmentRecorder != null) {
                try {
                    currentSegmentRecorder.release();
                } catch (Exception releaseError) {
                    Log.w(TAG, "Error releasing failed segment recorder", releaseError);
                }
                currentSegmentRecorder = null;
            }
            throw new IOException("Failed to start segment recording", e);
        }
    }

    /**
     * Rotate to next segment - called by timer
     */
    private void rotateSegment() {
        try {
            synchronized (recordingLock) {
                if (!isActive.get()) {
                    return;
                }

                // For rolling recording, maxDuration defines the rolling window size, not when to stop
                // The recording should continue indefinitely until manually stopped
                // The rolling window is managed in addSegmentToBuffer() method

                // Stop current segment
                if (currentSegmentRecorder != null) {
                    try {
                        currentSegmentRecorder.stop();
                        currentSegmentRecorder.release();

                        // Add completed segment to buffer
                        File segmentFile = getSegmentFile(segmentCounter.get());
                        addSegmentToBuffer(segmentFile);

                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping segment during rotation", e);
                    } finally {
                        currentSegmentRecorder = null;
                    }
                }

                // Move to next segment
                segmentCounter.incrementAndGet();

                // Emit duration change event for consistency with linear recording
                if (durationChangeCallback != null) {
                    long currentDuration = getElapsedRecordingTime();
                    try {
                        durationChangeCallback.onDurationChanged(currentDuration);
                        Log.d(TAG, "Emitted duration change event: " + (currentDuration / 1000.0) + "s");
                    } catch (Exception e) {
                        Log.w(TAG, "Error emitting duration change event", e);
                    }
                }

                // Start new segment only if still active
                if (isActive.get()) {
                    startNewSegment();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to rotate segment", e);
        }
    }

    /**
     * Add segment to buffer with optimal memory usage rolling window management
     */
    private void addSegmentToBuffer(File segmentFile) {
        // Enhanced validation for edge cases
        if (segmentFile == null) {
            Log.w(TAG, "Null segment file provided, skipping");
            return;
        }

        // Check if file exists and has content
        if (!segmentFile.exists() || segmentFile.length() < 1024) { // Minimum 1KB
            Log.d(TAG, "Segment file too small or doesn't exist, skipping: " + segmentFile.getName() +
                  " (size: " + (segmentFile.exists() ? segmentFile.length() : "N/A") + " bytes)");
            if (segmentFile.exists()) {
                segmentFile.delete();
            }
            return;
        }

        // Validate segment file format (basic check)
        if (!segmentFile.getName().endsWith(".m4a")) {
            Log.w(TAG, "Unexpected segment file format: " + segmentFile.getName());
        }

        // Add to buffer
        segmentBuffer.offer(segmentFile);
        Log.d(TAG, "Added segment to buffer: " + segmentFile.getName() + " (" +
              String.format("%.2f", segmentFile.length() / 1024.0) + " KB, buffer size: " + segmentBuffer.size() + ")");

        // Check memory usage periodically
        checkMemoryUsage();

        // Optimize memory usage with rolling window management aligned with iOS
        if (maxDurationMs != null && maxDurationMs > 0) {
            // iOS parity: effectiveMaxSegments = ceil(maxDuration/segmentDuration) + 1
            int effectiveMaxSegments = (int) Math.ceil((double) maxDurationMs / (double) segmentDurationMs) + 1;
            if (effectiveMaxSegments < 1) effectiveMaxSegments = 1;

            int removedCount = 0;
            while (segmentBuffer.size() > effectiveMaxSegments) {
                File oldestSegment = segmentBuffer.poll();
                if (oldestSegment != null) {
                    if (enableSegmentCompression && oldestSegment.exists() && oldestSegment.length() > 0) {
                        compressSegmentBeforeDeletion(oldestSegment);
                    }
                    deleteSegmentWithRetry(oldestSegment);
                    removedCount++;
                }
            }
            if (removedCount > 0) {
                Log.d(TAG, "Batched removal of old segments - maintaining rolling window of " + effectiveMaxSegments + " segments");
            }
            if (enablePersistentIndexing) {
                updatePersistentIndex();
            }
        } else {
            // Unlimited mode: keep all segments (parity with iOS)
        }
        // Notify pre-merge worker that the plan changed
        requestPreMerge();
    }

    /**
     * Merge all segments in buffer into single audio file using MediaMuxer
     */
    private File mergeSegments() throws IOException {
        List<File> segmentList = new ArrayList<>(segmentBuffer);

        Log.d(TAG, "=== MERGE SEGMENTS START ===");
        Log.d(TAG, "Total segments in buffer: " + segmentList.size());

        if (segmentList.isEmpty()) {
            Log.e(TAG, "No segments to merge - this should not happen!");
            throw new IOException("No segments to merge");
        }

        // Log all available segments for debugging
        for (int i = 0; i < segmentList.size(); i++) {
            File segment = segmentList.get(i);
            long segmentSize = segment.exists() ? segment.length() : 0;
            Log.d(TAG, "  Segment " + i + ": " + segment.getName() + " (" + segmentSize + " bytes)");
        }

        // If maxDuration is set, calculate which segments to use and how much of each
        List<File> segmentsToMerge = segmentList;
        double startTimeInFirstSegment = 0.0; // How much to skip from the first segment

        if (maxDurationMs != null && maxDurationMs > 0) {
            double maxDurationSeconds = maxDurationMs / 1000.0;

            Log.d(TAG, "Merge calculation: maxDuration=" + maxDurationSeconds + "s");
            Log.d(TAG, "Available segments: " + segmentList.size());

            // Calculate actual audio durations for all segments
            List<Double> segmentDurations = new ArrayList<>();
            double totalActualAudioSeconds = 0.0;

            for (int i = 0; i < segmentList.size(); i++) {
                File segmentFile = segmentList.get(i);
                try {
                    double segmentDurationSeconds = getAudioFileDuration(segmentFile) / 1000.0;
                    segmentDurations.add(segmentDurationSeconds);
                    totalActualAudioSeconds += segmentDurationSeconds;
                    Log.d(TAG, "Segment " + i + " actual duration: " + String.format("%.3f", segmentDurationSeconds) + "s");
                } catch (IOException e) {
                    // Fallback to estimated duration if we can't read the file
                    double fallbackDuration = segmentDurationMs / 1000.0;
                    if (i == segmentList.size() - 1) {
                        // For the last segment, use more accurate estimation
                        long totalRecordingMs = System.currentTimeMillis() - recordingStartTime;

                        // For maxDuration recordings, use the target duration instead of elapsed time
                        if (maxDurationMs != null && maxDurationMs > 0) {
                            // If we have maxDuration, the last segment should contain exactly what we need
                            double remainingDuration = maxDurationMs / 1000.0;
                            for (int j = 0; j < i; j++) {
                                remainingDuration -= segmentDurations.get(j);
                            }
                            fallbackDuration = Math.max(0.1, Math.min(remainingDuration, segmentDurationMs / 1000.0));
                            Log.d(TAG, "Last segment estimated duration (maxDuration mode): " + String.format("%.3f", fallbackDuration) + "s");
                        } else {
                            // Standard elapsed-time calculation for rolling recordings
                            long currentSegmentElapsed = (totalRecordingMs % segmentDurationMs);
                            fallbackDuration = Math.min(currentSegmentElapsed / 1000.0, segmentDurationMs / 1000.0);
                            Log.d(TAG, "Last segment estimated duration (elapsed time): " + String.format("%.3f", fallbackDuration) + "s");
                        }
                    }
                    segmentDurations.add(fallbackDuration);
                    totalActualAudioSeconds += fallbackDuration;
                    Log.w(TAG, "Could not read duration for segment " + i + ", using estimated " + String.format("%.3f", fallbackDuration) + "s", e);
                }
            }

            Log.d(TAG, "Total actual audio available: " + String.format("%.3f", totalActualAudioSeconds) + "s");

            if (totalActualAudioSeconds > maxDurationSeconds) {
                // We need to trim to keep exactly maxDurationSeconds from the end
                Log.d(TAG, "Need to trim " + String.format("%.3f", totalActualAudioSeconds - maxDurationSeconds) + "s from the beginning");

                // Calculate exactly which segments to use and how much to trim
                double audioToKeep = maxDurationSeconds;
                double audioToSkipFromBeginning = totalActualAudioSeconds - maxDurationSeconds;

                Log.d(TAG, "Audio to skip from beginning: " + String.format("%.3f", audioToSkipFromBeginning) + "s");
                Log.d(TAG, "Audio to keep: " + String.format("%.3f", audioToKeep) + "s");

                // Find the first segment to include and how much to skip from it
                double cumulativeSkipped = 0.0;
                int segmentsToSkip = 0;
                double remainingTimeToSkip = audioToSkipFromBeginning;

                for (int i = 0; i < segmentDurations.size(); i++) {
                    double segmentDuration = segmentDurations.get(i);

                    if (remainingTimeToSkip >= segmentDuration) {
                        // Skip this entire segment
                        remainingTimeToSkip -= segmentDuration;
                        segmentsToSkip++;
                        cumulativeSkipped += segmentDuration;
                        Log.d(TAG, "Skipping entire segment " + i + " (" + String.format("%.3f", segmentDuration) + "s)");
                    } else {
                        // This is the first segment we'll keep, but we need to skip part of it
                        startTimeInFirstSegment = remainingTimeToSkip;
                        Log.d(TAG, "Keeping segment " + i + " starting from " + String.format("%.3f", startTimeInFirstSegment) + "s");
                        break;
                    }
                }

                // Ensure we don't skip more segments than we have, and always keep at least 1 segment
                segmentsToSkip = Math.min(segmentsToSkip, segmentList.size() - 1);
                segmentsToSkip = Math.max(0, segmentsToSkip);

                if (segmentsToSkip > 0) {
                    segmentsToMerge = segmentList.subList(segmentsToSkip, segmentList.size());
                    Log.d(TAG, "Skipping first " + segmentsToSkip + " segments, using segments " + segmentsToSkip + " to " + (segmentList.size() - 1));
                }

                // Calculate the expected output duration for verification
                double expectedOutputDuration = 0.0;
                for (int i = segmentsToSkip; i < segmentDurations.size(); i++) {
                    double segmentContribution = segmentDurations.get(i);
                    if (i == segmentsToSkip) {
                        // First segment - subtract the skipped portion
                        segmentContribution -= startTimeInFirstSegment;
                    }
                    expectedOutputDuration += segmentContribution;
                }

                // Trim the output to exactly maxDurationSeconds if it's longer
                if (expectedOutputDuration > maxDurationSeconds) {
                    expectedOutputDuration = maxDurationSeconds;
                }

                Log.d(TAG, "Will skip " + String.format("%.3f", startTimeInFirstSegment) + "s from first segment being merged");
                Log.d(TAG, "Final segments to merge: " + segmentsToMerge.size() + " segments");
                Log.d(TAG, "Expected output duration: " + String.format("%.3f", expectedOutputDuration) + "s (target: " + String.format("%.3f", maxDurationSeconds) + "s)");

            } else {
                Log.d(TAG, "Total audio (" + String.format("%.3f", totalActualAudioSeconds) + "s) is less than or equal to maxDuration (" + String.format("%.3f", maxDurationSeconds) + "s), using all segments");
            }
        }

        // Final segments to merge
        Log.d(TAG, "Final segments selected for merge: " + segmentsToMerge.size());
        if (segmentsToMerge != segmentList) {
            Log.d(TAG, "Segments filtered - original: " + segmentList.size() + ", selected: " + segmentsToMerge.size());
            for (int i = 0; i < segmentsToMerge.size(); i++) {
                File segment = segmentsToMerge.get(i);
                Log.d(TAG, "  Selected segment " + i + ": " + segment.getName());
            }
        }
        Log.d(TAG, "Start time in first segment: " + startTimeInFirstSegment + "s");

        // Final validation
        if (segmentsToMerge.isEmpty()) {
            Log.e(TAG, "No segments selected for merging after filtering!");
            throw new IOException("No segments available for merging after filtering");
        }

        // Create output file
        File outputFile = new File(segmentsDirectory.getParent(),
            "merged_recording_" + System.currentTimeMillis() + ".m4a");

        Log.d(TAG, "Creating output file: " + outputFile.getAbsolutePath());

        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>();

        try {
            // Create MediaMuxer for output
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Get audio track format from first segment
            MediaExtractor firstExtractor = new MediaExtractor();
            firstExtractor.setDataSource(segmentsToMerge.get(0).getAbsolutePath());
            extractors.add(firstExtractor);

            MediaFormat audioFormat = null;
            int audioTrackIndex = -1;

            for (int i = 0; i < firstExtractor.getTrackCount(); i++) {
                MediaFormat format = firstExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioFormat = format;
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioFormat == null) {
                throw new IOException("No audio track found in segments");
            }

            // Add audio track to muxer
            int muxerTrackIndex = muxer.addTrack(audioFormat);
            muxer.start();

            // Process each segment with smart trimming
            long globalPresentationTimeUs = 0;
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            // Calculate max duration in microseconds for precise stopping
            long maxDurationUs = maxDurationMs != null ? maxDurationMs * 1000 : Long.MAX_VALUE;
            boolean reachedMaxDuration = false;

            for (int segmentIndex = 0; segmentIndex < segmentsToMerge.size() && !reachedMaxDuration; segmentIndex++) {
                File segmentFile = segmentsToMerge.get(segmentIndex);
                MediaExtractor extractor = new MediaExtractor();
                extractor.setDataSource(segmentFile.getAbsolutePath());
                extractors.add(extractor);

                // Find audio track
                int trackIndex = -1;
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        trackIndex = i;
                        break;
                    }
                }

                if (trackIndex == -1) {
                    Log.w(TAG, "No audio track in segment: " + segmentFile.getName());
                    continue;
                }

                extractor.selectTrack(trackIndex);

                // For the first segment, seek to the start time if needed
                if (segmentIndex == 0 && startTimeInFirstSegment > 0) {
                    long seekTimeUs = (long) (startTimeInFirstSegment * 1000000);
                    extractor.seekTo(seekTimeUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    Log.d(TAG, "Seeking to " + String.format("%.3f", startTimeInFirstSegment) + "s in first segment");
                }

                // Copy audio data with proper timing and duration control
                long segmentStartTimeUs = globalPresentationTimeUs;
                long lastSampleTimeUs = -1;

                while (true) {
                    // Check if we've reached the maximum duration
                    if (maxDurationMs != null && globalPresentationTimeUs >= maxDurationUs) {
                        Log.d(TAG, "Reached maximum duration limit at " + String.format("%.3f", globalPresentationTimeUs / 1000000.0) + "s");
                        reachedMaxDuration = true;
                        break;
                    }

                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        break; // End of segment
                    }

                    // Get the actual sample timestamp from the extractor
                    long originalSampleTimeUs = extractor.getSampleTime();

                    bufferInfo.offset = 0;
                    bufferInfo.size = sampleSize;
                    bufferInfo.presentationTimeUs = globalPresentationTimeUs;

                    bufferInfo.flags = getCodecFlags(extractor);

                    muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo);

                    // Calculate the actual sample duration from the source
                    if (lastSampleTimeUs != -1) {
                        long actualSampleDurationUs = originalSampleTimeUs - lastSampleTimeUs;
                        // Use actual sample duration if reasonable, otherwise fallback to estimated
                        if (actualSampleDurationUs > 0 && actualSampleDurationUs < 100000) { // Less than 100ms per sample
                            globalPresentationTimeUs += actualSampleDurationUs;
                        } else {
                            // Fallback to estimated duration for AAC
                            long estimatedDurationUs = (1024L * 1000000L) / recordingConfig.getSampleRate();
                            globalPresentationTimeUs += estimatedDurationUs;
                        }
                    } else {
                        // For first sample, use estimated duration
                        long estimatedDurationUs = (1024L * 1000000L) / recordingConfig.getSampleRate();
                        globalPresentationTimeUs += estimatedDurationUs;
                    }

                    lastSampleTimeUs = originalSampleTimeUs;
                    extractor.advance();
                }

                long segmentDurationUs = globalPresentationTimeUs - segmentStartTimeUs;
                Log.d(TAG, "Merged segment " + segmentIndex + ": " + segmentFile.getName() + " (" + String.format("%.3f", segmentDurationUs / 1000000.0) + "s)");

                if (reachedMaxDuration) {
                    Log.d(TAG, "Stopping merge at segment " + segmentIndex + " due to max duration constraint");
                    break;
                }
            }

            Log.d(TAG, "Successfully merged " + segmentsToMerge.size() + " segments into: " + outputFile.getName() +
                  " (total duration: " + String.format("%.3f", globalPresentationTimeUs / 1000000.0) + "s)" +
                  (maxDurationMs != null ? ", target: " + String.format("%.3f", maxDurationMs / 1000.0) + "s" : ""));

            // Final verification: check actual duration of merged file and trim if necessary
            if (maxDurationMs != null && maxDurationMs > 0) {
                try {
                    long actualDurationMs = getAudioFileDuration(outputFile);
                    double actualDurationSeconds = actualDurationMs / 1000.0;
                    double targetDurationSeconds = maxDurationMs / 1000.0;
                    double durationDifference = Math.abs(actualDurationSeconds - targetDurationSeconds);

                    Log.d(TAG, "Final verification: actual=" + String.format("%.3f", actualDurationSeconds) +
                          "s, target=" + String.format("%.3f", targetDurationSeconds) +
                          "s, difference=" + String.format("%.3f", durationDifference) + "s");

                    // For maxDuration recordings, always do precision trim to ensure exact duration
                    // This ensures we get exactly the requested duration every time
                    if (durationDifference > 0.01 || actualDurationSeconds != targetDurationSeconds) {
                        Log.d(TAG, "Duration difference (" + String.format("%.3f", durationDifference) +
                              "s) detected, performing final precision trim for exact duration");

                        File precisionTrimmedFile = new File(segmentsDirectory.getParent(),
                            "final_trimmed_recording_" + System.currentTimeMillis() + ".m4a");

                        // Use AudioFileProcessor for final precision trimming
                        try {
                            boolean fastOk2 = fastTrimFile(outputFile, precisionTrimmedFile, 0.0, targetDurationSeconds);
                            if (!fastOk2) {
                                AudioFileProcessor.trimAudioFile(outputFile, precisionTrimmedFile, 0.0, targetDurationSeconds);
                            }

                            // Verify the trimmed file
                            long trimmedDurationMs = getAudioFileDuration(precisionTrimmedFile);
                            double trimmedDurationSeconds = trimmedDurationMs / 1000.0;
                            Log.d(TAG, "Final trimmed duration: " + String.format("%.3f", trimmedDurationSeconds) + "s");

                            // Delete the original merged file and use the trimmed one
                            if (outputFile.delete()) {
                                Log.d(TAG, "Deleted original merged file");
                            }
                            outputFile = precisionTrimmedFile;

                        } catch (Exception e) {
                            Log.w(TAG, "Final precision trim failed, using original merged file", e);
                            // Clean up failed trim attempt
                            if (precisionTrimmedFile.exists()) {
                                precisionTrimmedFile.delete();
                            }
                        }
                    } else {
                        Log.d(TAG, "Duration difference within tolerance, no final trim needed");
                    }

                } catch (Exception e) {
                    Log.w(TAG, "Could not verify final duration, using merged file as-is", e);
                }
            }

            return outputFile;

        } finally {
            // Cleanup resources
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMuxer", e);
                }
            }

            for (MediaExtractor extractor : extractors) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaExtractor", e);
                }
            }
        }
    }

    private static int getCodecFlags(MediaExtractor extractor) {
        int extractorFlags = extractor.getSampleFlags();
        int codecFlags = 0;

        // Map MediaExtractor flags to MediaCodec flags
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }
        return codecFlags;
    }

    /**
     * Get file for segment
     */
    private File getSegmentFile(int index) {
        return new File(segmentsDirectory, "segment_" + index + ".m4a");
    }

    /**
     * Clean up all segment files
     */
    private void cleanupSegments() {
        for (File segmentFile : segmentBuffer) {
            if (segmentFile.exists()) {
                segmentFile.delete();
            }
        }
        segmentBuffer.clear();
    }

    /**
     * Clean up old segments from previous sessions
     */
    private void cleanupOldSegments() {
        if (!segmentsDirectory.exists()) {
            return;
        }

        File[] files = segmentsDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().endsWith(".m4a")) {
                    if (file.delete()) {
                        Log.d(TAG, "Cleaned up old segment: " + file.getName());
                    }
                }
            }
        }
    }

    /**
     * Release all resources
     */
    public void release() {
        synchronized (recordingLock) {
            if (segmentTimer != null) {
                segmentTimer.cancel();
                segmentTimer = null;
            }

            if (durationUpdateTimer != null) {
                durationUpdateTimer.cancel();
                durationUpdateTimer = null;
            }

            // Stop background workers
            stopPreMergeWorker();
            stopContinuousWindowWorker();

            if (currentSegmentRecorder != null) {
                try {
                    currentSegmentRecorder.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping recorder during release", e);
                }
                try {
                    currentSegmentRecorder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing recorder", e);
                }
                currentSegmentRecorder = null;
            }

            // Release continuous recorder as well
            if (continuousRecorder != null) {
                try {
                    if (isContinuousActive) {
                        continuousRecorder.stop();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping continuous recorder during release", e);
                }
                try {
                    continuousRecorder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing continuous recorder", e);
                }
                continuousRecorder = null;
                isContinuousActive = false;
            }

            isActive.set(false);
            recordingStartTime = 0;

            // Reset pause tracking state
            isDurationPaused.set(false);
            pausedDurationOffset = 0;
            pauseStartTime = 0;
            isManuallyPaused.set(false);
            manualPausedDurationOffset = 0;
            manualPauseStartTime = 0;

            cleanupSegments();
        }
    }

    /**
     * Monitor and log memory usage of segment buffer
     */
    private void checkMemoryUsage() {
        long currentTime = System.currentTimeMillis();

        // Only check memory periodically to avoid performance impact
        if (currentTime - lastMemoryCheckTime < MEMORY_CHECK_INTERVAL_MS) {
            return;
        }
        lastMemoryCheckTime = currentTime;

        // Calculate total memory usage of segments
        long totalBytes = 0;
        int segmentCount = 0;

        for (File segment : segmentBuffer) {
            if (segment.exists()) {
                totalBytes += segment.length();
                segmentCount++;
            }
        }

        totalSegmentBytes = totalBytes;

        // Convert to readable format
        double totalMB = totalBytes / (1024.0 * 1024.0);
        double avgSegmentMB = segmentCount > 0 ? totalMB / segmentCount : 0;

        // Calculate expected segments for current duration
        long recordingDurationMs = System.currentTimeMillis() - recordingStartTime;
    int expectedSegments = (int) Math.ceil((double) recordingDurationMs / segmentDurationMs);

        // Log memory status
        Log.d(TAG, String.format("Memory Status: %.2f MB total, %d segments (avg %.2f MB each), expected %d segments for %ds recording",
              totalMB, segmentCount, avgSegmentMB, expectedSegments, recordingDurationMs / 1000));

        // Dynamic memory warning thresholds based on maxDuration
        double memoryWarningThreshold = 100.0; // Default 100MB baseline

        if (maxDurationMs != null && maxDurationMs > 0 && avgSegmentMB > 0) {
            // Calculate expected maximum memory usage based on maxDuration using configurable safety margin
            int maxExpectedSegments = (int) Math.ceil((double) maxDurationMs / segmentDurationMs) + segmentSafetyBuffer;
            double expectedMaxMB = maxExpectedSegments * avgSegmentMB * memorySafetyMargin;

            // Use the higher of baseline (100MB) or calculated expected maximum
            memoryWarningThreshold = Math.max(100.0, expectedMaxMB);

            Log.d(TAG, String.format("Dynamic memory threshold: %.2f MB (expected max %d segments × %.2f MB × %.1f safety)",
                  memoryWarningThreshold, maxExpectedSegments, avgSegmentMB, memorySafetyMargin));
        }

        // Warn if memory usage exceeds dynamic threshold
        if (totalMB > memoryWarningThreshold) {
            Log.w(TAG, String.format("HIGH MEMORY WARNING: Segment buffer using %.2f MB (threshold: %.2f MB)",
                  totalMB, memoryWarningThreshold));
        }

        // Warn if we have way more segments than expected
        if (maxDurationMs != null && segmentCount > expectedSegments * 2) {
            Log.w(TAG, "SEGMENT COUNT WARNING: " + segmentCount + " segments vs expected " + expectedSegments +
                  " for " + (recordingDurationMs / 1000) + "s recording");
        }

        // Additional warning for excessive segment count regardless of maxDuration
        if (segmentCount > expectedSegments * 3) {
            Log.w(TAG, "EXCESSIVE SEGMENT WARNING: " + segmentCount + " segments for " +
                  (recordingDurationMs / 1000) + "s recording (3x expected)");
        }
    }

    /**
     * Get current memory usage statistics
     */
    public String getMemoryStats() {
        checkMemoryUsage(); // Update stats

        double totalMB = totalSegmentBytes / (1024.0 * 1024.0);
        int segmentCount = segmentBuffer.size();
        double avgSegmentMB = segmentCount > 0 ? totalMB / segmentCount : 0;

        long recordingDurationMs = recordingStartTime > 0 ? System.currentTimeMillis() - recordingStartTime : 0;
        double recordingMinutes = recordingDurationMs / 60000.0;

        // Calculate efficiency metrics
        String efficiency = "";
        if (maxDurationMs != null && maxDurationMs > 0) {
            int maxExpectedSegments = (int) Math.ceil((double) maxDurationMs / segmentDurationMs) + 1;
            double maxExpectedMB = maxExpectedSegments * avgSegmentMB;
            double efficiencyPercent = maxExpectedMB > 0 ? (totalMB / maxExpectedMB) * 100 : 0;
            efficiency = String.format(", Efficiency: %.1f%% (using %.1f/%.1f MB)",
                                     efficiencyPercent, totalMB, maxExpectedMB);
        }

        return String.format("Memory: %.2f MB, Segments: %d (avg %.2f MB), Duration: %.1fm%s",
                           totalMB, segmentCount, avgSegmentMB, recordingMinutes, efficiency);
    }

    /**
     * Get detailed memory and performance statistics
     */
    public String getDetailedStats() {
        checkMemoryUsage(); // Update stats

        StringBuilder stats = new StringBuilder();

        double totalMB = totalSegmentBytes / (1024.0 * 1024.0);
        int segmentCount = segmentBuffer.size();
        double avgSegmentMB = segmentCount > 0 ? totalMB / segmentCount : 0;

        long recordingDurationMs = recordingStartTime > 0 ? System.currentTimeMillis() - recordingStartTime : 0;
        double recordingMinutes = recordingDurationMs / 60000.0;
    int expectedSegments = (int) Math.ceil((double) recordingDurationMs / segmentDurationMs);

        stats.append("=== Segment Rolling Statistics ===\n");
        stats.append(String.format("Recording Duration: %.1f minutes (%.0f seconds)\n", recordingMinutes, recordingDurationMs / 1000.0));
        stats.append(String.format("Total Memory Usage: %.2f MB\n", totalMB));
        stats.append(String.format("Segment Count: %d (expected: %d)\n", segmentCount, expectedSegments));
        stats.append(String.format("Average Segment Size: %.2f MB\n", avgSegmentMB));
    stats.append(String.format("Segment Duration: %d seconds\n", segmentDurationMs / 1000));

        if (maxDurationMs != null && maxDurationMs > 0) {
            int maxExpectedSegments = (int) Math.ceil((double) maxDurationMs / segmentDurationMs) + 1;
            double maxExpectedMB = maxExpectedSegments * avgSegmentMB;
            double memoryEfficiency = maxExpectedMB > 0 ? (totalMB / maxExpectedMB) * 100 : 0;

            stats.append(String.format("Max Duration: %.1f minutes\n", maxDurationMs / 60000.0));
            stats.append(String.format("Max Expected Segments: %d\n", maxExpectedSegments));
            stats.append(String.format("Memory Efficiency: %.1f%% (%.2f/%.2f MB)\n", memoryEfficiency, totalMB, maxExpectedMB));

            // Calculate rolling window effectiveness
            double rollingWindowSeconds = Math.min(recordingDurationMs / 1000.0, maxDurationMs / 1000.0);
            stats.append(String.format("Rolling Window: %.1f seconds (%.1f%% of total recording)\n",
                        rollingWindowSeconds, (rollingWindowSeconds / (recordingDurationMs / 1000.0)) * 100));
        }

        stats.append("Active: ").append(isActive.get()).append("\n");
        stats.append("Segment Counter: ").append(segmentCounter.get()).append("\n");

        return stats.toString();
    }

    // ==================== ERROR RECOVERY AND ENHANCEMENT METHODS ====================

    /**
     * Delete a segment file with retry logic for improved error recovery
     * @param segmentFile The file to delete
     * @return true if deletion was successful, false otherwise
     */
    private boolean deleteSegmentWithRetry(File segmentFile) {
        if (segmentFile == null || !segmentFile.exists()) {
            return true; // Consider non-existent files as successfully "deleted"
        }

        for (int attempt = 1; attempt <= MAX_DELETION_RETRIES; attempt++) {
            try {
                if (segmentFile.delete()) {
                    Log.d(TAG, "Successfully deleted segment: " + segmentFile.getName() +
                          (attempt > 1 ? " (attempt " + attempt + ")" : ""));
                    return true;
                }

                Log.w(TAG, "Failed to delete segment: " + segmentFile.getName() +
                      " (attempt " + attempt + "/" + MAX_DELETION_RETRIES + ")");

                // Wait before retrying (except on last attempt)
                if (attempt < MAX_DELETION_RETRIES) {
                    Thread.sleep(DELETION_RETRY_DELAY_MS);
                }

            } catch (InterruptedException e) {
                Log.w(TAG, "Deletion retry interrupted for: " + segmentFile.getName());
                Thread.currentThread().interrupt();
                return false;
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception deleting segment: " + segmentFile.getName(), e);
                return false;
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error deleting segment: " + segmentFile.getName(), e);
            }
        }

        Log.e(TAG, "Failed to delete segment after " + MAX_DELETION_RETRIES + " attempts: " +
              segmentFile.getName());
        return false;
    }

    /**
     * Compress a segment file before deletion to save space (if compression is enabled)
     * This is a placeholder implementation - in practice, you might want to use
     * Android's built-in compression or a third-party library
     * @param segmentFile The file to compress
     */
    private void compressSegmentBeforeDeletion(File segmentFile) {
        if (!enableSegmentCompression || segmentFile == null || !segmentFile.exists()) {
            return;
        }

        try {
            // For now, this is a placeholder implementation
            // In a real implementation, you might:
            // 1. Use java.util.zip.GZIPOutputStream to compress the file
            // 2. Use Android's built-in compression utilities
            // 3. Move the file to a compressed archive
            // 4. Use a third-party compression library

            Log.d(TAG, "Compression placeholder for segment: " + segmentFile.getName() +
                  " (original size: " + String.format("%.2f", segmentFile.length() / 1024.0) + " KB)");

            // Example compression logic would go here
            // For demonstration, we'll just log the action

        } catch (Exception e) {
            Log.w(TAG, "Failed to compress segment before deletion: " + segmentFile.getName(), e);
        }
    }

    /**
     * Update the persistent index file for crash recovery
     */
    private void updatePersistentIndex() {
        if (!enablePersistentIndexing || indexFile == null) {
            return;
        }

        try {
            // Create a simple JSON-like index of current segments
            StringBuilder indexContent = new StringBuilder();
            indexContent.append("{\n");
            indexContent.append("  \"timestamp\": ").append(System.currentTimeMillis()).append(",\n");
            indexContent.append("  \"recordingStartTime\": ").append(recordingStartTime).append(",\n");
            indexContent.append("  \"segmentCounter\": ").append(segmentCounter.get()).append(",\n");
            indexContent.append("  \"maxDurationMs\": ").append(maxDurationMs).append(",\n");
            indexContent.append("  \"segments\": [\n");

            boolean first = true;
            for (File segment : segmentBuffer) {
                if (!first) {
                    indexContent.append(",\n");
                }
                indexContent.append("    {\n");
                indexContent.append("      \"name\": \"").append(segment.getName()).append("\",\n");
                indexContent.append("      \"path\": \"").append(segment.getAbsolutePath()).append("\",\n");
                indexContent.append("      \"size\": ").append(segment.exists() ? segment.length() : 0).append(",\n");
                indexContent.append("      \"exists\": ").append(segment.exists()).append("\n");
                indexContent.append("    }");
                first = false;
            }

            indexContent.append("\n  ]\n");
            indexContent.append("}\n");

            // Write to file
            java.io.FileWriter writer = new java.io.FileWriter(indexFile);
            writer.write(indexContent.toString());
            writer.close();

            Log.d(TAG, "Updated persistent index with " + segmentBuffer.size() + " segments");

        } catch (Exception e) {
            Log.w(TAG, "Failed to update persistent index", e);
        }
    }

    /**
     * Recover from persistent index after a crash or restart
     */
    private void recoverFromPersistentIndex() {
        if (!enablePersistentIndexing || indexFile == null || !indexFile.exists()) {
            Log.d(TAG, "No persistent index found for recovery");
            return;
        }

        try {
            // Read and parse the index file
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(indexFile));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
            reader.close();

            Log.d(TAG, "Found persistent index, attempting recovery...");

            // Simple parsing - in a production environment, you'd want to use a proper JSON parser
            String indexContent = content.toString();

            // Extract segment information (basic string parsing for demonstration)
            // In production, use a proper JSON library like org.json or Gson
            if (indexContent.contains("\"segments\": [")) {
                Log.d(TAG, "Persistent index contains segment information");

                // For now, just validate that the segments directory exists and clean up any orphaned files
                if (segmentsDirectory.exists()) {
                    File[] existingFiles = segmentsDirectory.listFiles();
                    if (existingFiles != null) {
                        int recoveredCount = 0;
                        for (File file : existingFiles) {
                            if (file.getName().startsWith("segment_") && file.getName().endsWith(".m4a")) {
                                recoveredCount++;
                            }
                        }
                        Log.d(TAG, "Found " + recoveredCount + " existing segment files for potential recovery");
                    }
                }
            }

            // Clean up the index file after recovery attempt
            indexFile.delete();
            Log.d(TAG, "Completed recovery attempt and cleaned up index file");

        } catch (Exception e) {
            Log.w(TAG, "Failed to recover from persistent index", e);
            // Clean up corrupted index file
            if (indexFile.exists()) {
                indexFile.delete();
            }
        }
    }

    /**
     * Get configuration summary for debugging
     */
    public String getConfigurationSummary() {
        return String.format(
            "SegmentRollingManager Configuration:\n" +
            "- Segment Duration: %dms (%.1fs)\n" +
            "- Memory Safety Margin: %.1fx\n" +
            "- Segment Safety Buffer: %d\n" +
            "- Compression Enabled: %s\n" +
            "- Persistent Indexing: %s\n" +
            "- Max Deletion Retries: %d\n" +
            "- Deletion Retry Delay: %dms",
            segmentDurationMs,
            segmentDurationMs / 1000.0,
            memorySafetyMargin,
            segmentSafetyBuffer,
            enableSegmentCompression ? "Yes" : "No",
            enablePersistentIndexing ? "Yes" : "No",
            MAX_DELETION_RETRIES,
            DELETION_RETRY_DELAY_MS
        );
    }

    /**
     * Debug method to get current segment rolling state
     */
    public String getSegmentRollingDebugInfo() {
        if (!isActive.get()) {
            return "Segment rolling is not active";
        }

        long currentTime = System.currentTimeMillis();
        long recordingDuration = currentTime - recordingStartTime;
    long currentSegmentElapsed = recordingDuration % segmentDurationMs;
    int expectedSegments = (int) Math.ceil((double) recordingDuration / segmentDurationMs);

        StringBuilder debug = new StringBuilder();
        debug.append("=== Segment Rolling Debug Info ===\n");
        debug.append(String.format("Recording Duration: %.1fs\n", recordingDuration / 1000.0));
        debug.append(String.format("Current Segment: %d (elapsed: %.1fs/%.1fs)\n",
                    segmentCounter.get(), currentSegmentElapsed / 1000.0, segmentDurationMs / 1000.0));
        debug.append(String.format("Buffer Size: %d segments\n", segmentBuffer.size()));
        debug.append(String.format("Expected Segments: %d\n", expectedSegments));

        if (maxDurationMs != null) {
            debug.append(String.format("Max Duration: %.1fs\n", maxDurationMs / 1000.0));
            int minSegmentsNeeded = Math.max(2, (int) Math.ceil((double) maxDurationMs / segmentDurationMs));
            int maxSegmentsToKeep = minSegmentsNeeded + segmentSafetyBuffer;
            debug.append(String.format("Min Segments Needed: %d, Max to Keep: %d\n", minSegmentsNeeded, maxSegmentsToKeep));
        }

        debug.append("Segments in buffer:\n");
        int index = 0;
        for (File segment : segmentBuffer) {
            debug.append(String.format("  [%d] %s (%s KB)\n", index++, segment.getName(),
                        segment.exists() ? String.format("%.1f", segment.length() / 1024.0) : "missing"));
        }

        return debug.toString();
    }

    // MARK: - Audio Interruption Callback Implementation

    /**
     * Handle audio interruption began (phone calls, audio focus loss, etc.)
     * Strategy: Pause duration tracking for ALL interruptions to provide accurate recording time
     */
    @Override
    public void onInterruptionBegan(AudioInterruptionManager.InterruptionType type) {
        Log.d(TAG, "Audio interruption began: " + type);

        synchronized (recordingLock) {
            if (!isActive.get()) {
                return; // Not recording, nothing to interrupt
            }

            lastInterruptionType = type;

            // Pause duration tracking for ALL interruptions to ensure accurate timing
            pauseDurationTracking();

            switch (type) {
                case PHONE_CALL:
                    Log.d(TAG, "Critical interruption (Phone call) - pausing recording and duration");
                    wasInterruptedByCall = true;
                    pauseSegmentRolling();
                    break;

                case AUDIO_FOCUS_LOSS:
                    Log.d(TAG, "Audio focus loss interruption - continuing recording but pausing duration");
                    // Don't pause recording for permanent audio focus loss, but pause duration tracking
                    break;

                case AUDIO_FOCUS_LOSS_TRANSIENT:
                case AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.d(TAG, "Temporary audio focus loss - continuing recording but pausing duration");
                    // Don't pause recording for temporary focus loss, but pause duration tracking
                    break;

                case SYSTEM_NOTIFICATION:
                    Log.d(TAG, "System notification interruption - continuing recording but pausing duration");
                    // Don't pause recording for system notifications, but pause duration tracking
                    break;

                case HEADPHONE_DISCONNECT:
                    Log.d(TAG, "Headphone disconnected - continuing recording on built-in microphone but pausing duration");
                    // Continue recording on built-in microphone, but pause duration tracking
                    break;

                default:
                    Log.d(TAG, "Unknown interruption type - continuing recording but pausing duration");
                    // For unknown types, log and continue recording but pause duration tracking
                    break;
            }
        }
    }

        /**
     * Handle audio interruption ended
     * Resume duration tracking for all interruptions and recording for critical ones
     */
    @Override
    public void onInterruptionEnded(AudioInterruptionManager.InterruptionType type, boolean shouldResume) {
        Log.d(TAG, "Audio interruption ended: " + type + ", should resume: " + shouldResume);

        synchronized (recordingLock) {
            if (!isActive.get()) {
                return; // Not in recording session
            }

            if (lastInterruptionType != type) {
                Log.w(TAG, "Interruption type mismatch - expected: " + lastInterruptionType + ", got: " + type);
                return;
            }

            if (!shouldResume) {
                Log.d(TAG, "Should not resume recording after interruption");
                return;
            }

            // Resume duration tracking for ALL interruption types
            resumeDurationTracking();

            switch (type) {
                case PHONE_CALL:
                    if (wasInterruptedByCall) {
                        Log.d(TAG, "Phone call ended - resuming recording and duration");
                        try {
                            resumeSegmentRolling();
                            wasInterruptedByCall = false;
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to resume recording after phone call", e);
                        }
                    }
                    break;

                case AUDIO_FOCUS_LOSS_TRANSIENT:
                case AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    Log.d(TAG, "Temporary audio focus restored - resuming duration tracking (recording was continuing)");
                    // Recording was not paused for these, but duration tracking was paused
                    break;

                case AUDIO_FOCUS_LOSS:
                    Log.d(TAG, "Audio focus restored - resuming duration tracking (recording was continuing)");
                    // Recording was not paused for permanent focus loss, but duration tracking was paused
                    break;

                case SYSTEM_NOTIFICATION:
                    Log.d(TAG, "System notification ended - resuming duration tracking (recording was continuing)");
                    // Recording was not paused for system notifications, but duration tracking was paused
                    break;

                default:
                    Log.d(TAG, "Unknown interruption ended - resuming duration tracking (recording was continuing)");
                    // Recording was not paused for unknown interruptions, but duration tracking was paused
                    break;
            }

            lastInterruptionType = null;
        }
    }

    /**
     * Handle audio route changes (headphone connect/disconnect, etc.)
     */
    @Override
    public void onAudioRouteChanged(String reason) {
        Log.d(TAG, "Audio route changed: " + reason);

        synchronized (recordingLock) {
            if (!isActive.get()) {
                return; // Not recording
            }

            // Log the route change but continue recording
            // The MediaRecorder should automatically adapt to the new audio route
            Log.d(TAG, "Recording continuing with new audio route: " + reason);
        }
    }

    /**
     * Check if currently interrupted by audio session issues
     */
    public boolean isInterruptedByAudio() {
        return interruptionManager != null && interruptionManager.isInterrupted();
    }

    /**
     * Get current interruption type if any
     */
    public AudioInterruptionManager.InterruptionType getCurrentInterruption() {
        return interruptionManager != null ? interruptionManager.getCurrentInterruption() : null;
    }

    /**
     * Pause duration tracking during interruptions
     */
    private void pauseDurationTracking() {
        if (!isDurationPaused.get()) {
            isDurationPaused.set(true);
            pauseStartTime = System.currentTimeMillis();
            Log.d(TAG, "Duration tracking paused at " + pauseStartTime);
        }
    }

    /**
     * Resume duration tracking after interruptions
     */
    private void resumeDurationTracking() {
        if (isDurationPaused.get()) {
            long pauseEndTime = System.currentTimeMillis();
            // Calculate how long we were paused and add to offset
            long currentPauseDuration = pauseEndTime - pauseStartTime;
            pausedDurationOffset += currentPauseDuration;
            isDurationPaused.set(false);
            pauseStartTime = 0;
            Log.d(TAG, "Duration tracking resumed, current pause duration: " + currentPauseDuration + "ms, total paused time: " + pausedDurationOffset + "ms");
        }
    }

    /**
     * Clean up resources including audio interruption monitoring
     */
    public void cleanup() {
        synchronized (recordingLock) {
            // Stop interruption monitoring
            if (interruptionManager != null) {
                interruptionManager.stopMonitoring();
                Log.d(TAG, "Audio interruption monitoring cleanup completed");
            }

            // Clean up recording resources
            if (isActive.get()) {
                try {
                    stopSegmentRolling();
                } catch (IOException e) {
                    Log.w(TAG, "Error stopping segment rolling during cleanup", e);
                }
            }

            // Reset interruption state
            wasInterruptedByCall = false;
            lastInterruptionType = null;

            Log.d(TAG, "SegmentRollingManager cleanup completed");
        }
    }

    /**
     * Create a minimal empty audio file for cases where no segments exist (e.g., after reset)
     * This ensures stopRecording can return a valid file even when no actual recording occurred
     */
    private File createMinimalEmptyAudioFile() throws IOException {
        File emptyFile = new File(segmentsDirectory.getParent(),
            "empty_recording_" + System.currentTimeMillis() + ".m4a");

        Log.d(TAG, "Creating minimal empty audio file: " + emptyFile.getName());

        // Simple approach: just create an empty file
        // The calling code can handle this case appropriately
        try {
            if (emptyFile.createNewFile()) {
                Log.d(TAG, "Created empty audio file placeholder: " + emptyFile.getName() +
                      " (size: " + emptyFile.length() + " bytes)");
                return emptyFile;
            } else {
                throw new IOException("Failed to create empty file");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to create empty audio file", e);
            throw new IOException("Failed to create empty audio file after reset: " + e.getMessage());
        }
    }

    // ===== Background Pre-Merge Worker =====
    private void startPreMergeWorker() {
        if (preMergeRunning.get()) return;
        preMergeStopRequested.set(false);
        preMergeRunning.set(true);
        preMergeThread = new Thread(() -> {
            Log.d(TAG, "Pre-merge worker started");
            while (!preMergeStopRequested.get()) {
                try {
                    performPreMergeIfNeeded();
                    // Sleep lightly; will be notified on changes
                    synchronized (preMergeLock) {
                        preMergeLock.wait(300);
                    }
                } catch (InterruptedException ie) {
                    // ignore
                } catch (Exception e) {
                    Log.w(TAG, "Pre-merge worker error", e);
                }
            }
            // Final attempt to be up-to-date before exiting
            try { performPreMergeIfNeeded(); } catch (Exception e) { Log.w(TAG, "Final pre-merge failed", e); }
            preMergeRunning.set(false);
            Log.d(TAG, "Pre-merge worker stopped");
        }, "PreMergeWorker");
        preMergeThread.setDaemon(true);
        preMergeThread.start();
    }

    private void requestPreMerge() {
        mergePlanVersion.incrementAndGet();
        synchronized (preMergeLock) {
            preMergeLock.notifyAll();
        }
    }

    private void stopPreMergeWorker() {
        preMergeStopRequested.set(true);
        synchronized (preMergeLock) {
            preMergeLock.notifyAll();
        }
        try {
            if (preMergeThread != null) {
                preMergeThread.join(1500);
            }
        } catch (InterruptedException ignored) {}
    }

    private void performPreMergeIfNeeded() throws IOException {
        long target = mergePlanVersion.get();
        if (mergedVersion.get() == target) return; // already up-to-date
        // Snapshot current segments
        List<File> segments = new ArrayList<>(segmentBuffer);
        if (segments.isEmpty()) return;
        List<String> currentNames = new ArrayList<>();
        for (File f : segments) currentNames.add(f.getName());

        File workFile = new File(segmentsDirectory, ".merged_work_" + System.currentTimeMillis() + ".m4a");
        boolean canAppend = !preMergedNames.isEmpty()
                && mergedTempFile.exists() && mergedTempFile.length() > 0
                && isPrefix(preMergedNames, currentNames);
        if (canAppend) {
            // Append only newly added segments onto existing merged
            int startIdx = preMergedNames.size();
            List<File> newSegs = (startIdx < segments.size()) ? segments.subList(startIdx, segments.size()) : new ArrayList<>();
            buildMergedByAppending(mergedTempFile, newSegs, workFile);
        } else {
            // Full rebuild (e.g., after deletions/rollovers or first build)
            buildMergedFromSegments(segments, workFile);
        }
        // Atomic replace
        if (mergedTempFile.exists() && !mergedTempFile.delete()) {
            Log.w(TAG, "Failed deleting old merged_temp before replace");
        }
        boolean renamed = workFile.renameTo(mergedTempFile);
        if (!renamed) {
            Log.w(TAG, "Rename failed, rebuilding directly to mergedTempFile");
            if (canAppend) {
                int startIdx = preMergedNames.size();
                List<File> newSegs = (startIdx < segments.size()) ? segments.subList(startIdx, segments.size()) : new ArrayList<>();
                buildMergedByAppending(mergedTempFile, newSegs, mergedTempFile);
            } else {
                buildMergedFromSegments(segments, mergedTempFile);
            }
            if (workFile.exists()) workFile.delete();
        }
        preMergedNames.clear();
        preMergedNames.addAll(currentNames);
        preMergedDurationUs = getDurationUsFast(mergedTempFile);
        mergedVersion.set(target);
        Log.d(TAG, "Pre-merge updated to version " + target + " using " + (canAppend ? "append" : "rebuild") +
                ", size=" + mergedTempFile.length());
    }

    private boolean isPrefix(List<String> prefix, List<String> full) {
        if (prefix.size() > full.size()) return false;
        for (int i = 0; i < prefix.size(); i++) {
            if (!prefix.get(i).equals(full.get(i))) return false;
        }
        return true;
    }

    private void buildMergedByAppending(File existingMerged, List<File> newSegments, File outFile) throws IOException {
        // If no new segments, just copy existingMerged to outFile
        if ((newSegments == null || newSegments.isEmpty()) && existingMerged != null && existingMerged.exists()) {
            buildMergedFromSegments(java.util.Arrays.asList(existingMerged), outFile);
            return;
        }
        MediaMuxer muxer = null;
        MediaExtractor ex0 = null;
        try {
            long existingDurUs = existingMerged != null && existingMerged.exists() ? getDurationUsFast(existingMerged) : 0;
            // Determine audio format from either existing or first new segment
            MediaFormat audioFormat = null;
            if (existingMerged != null && existingMerged.exists()) {
                ex0 = new MediaExtractor();
                ex0.setDataSource(existingMerged.getAbsolutePath());
                for (int i = 0; i < ex0.getTrackCount(); i++) {
                    MediaFormat f = ex0.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) { audioFormat = f; break; }
                }
            }
            if (audioFormat == null && newSegments != null && !newSegments.isEmpty()) {
                MediaExtractor tmp = new MediaExtractor();
                try {
                    tmp.setDataSource(newSegments.get(0).getAbsolutePath());
                    for (int i = 0; i < tmp.getTrackCount(); i++) {
                        MediaFormat f = tmp.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) { audioFormat = f; break; }
                    }
                } finally { try { tmp.release(); } catch (Exception ignore) {} }
            }
            if (audioFormat == null) throw new IOException("No audio format for append");

            if (outFile.exists() && !outFile.delete()) {
                Log.w(TAG, "Failed to delete existing output before append: " + outFile.getAbsolutePath());
            }
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outTrack = muxer.addTrack(audioFormat);
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            long ptsOffsetUs = 0;
            // First, copy existing merged if present
            if (existingMerged != null && existingMerged.exists() && existingMerged.length() > 0) {
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(existingMerged.getAbsolutePath());
                    int tr = -1;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        MediaFormat f = ex.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) { tr = i; break; }
                    }
                    if (tr >= 0) {
                        ex.selectTrack(tr);
                        while (true) {
                            info.offset = 0;
                            info.size = ex.readSampleData(buffer, 0);
                            if (info.size < 0) break;
                            long pts = ex.getSampleTime();
                            if (pts < 0) break;
                            info.presentationTimeUs = pts; // keep original timeline
                            info.flags = ex.getSampleFlags();
                            muxer.writeSampleData(outTrack, buffer, info);
                            ex.advance();
                        }
                    }
                } finally { try { ex.release(); } catch (Exception ignore) {} }
                ptsOffsetUs = existingDurUs > 0 ? existingDurUs : 0;
            }
            // Append new segments
            if (newSegments != null) {
                for (File seg : newSegments) {
                    MediaExtractor ex = new MediaExtractor();
                    try {
                        ex.setDataSource(seg.getAbsolutePath());
                        int tr = -1;
                        for (int i = 0; i < ex.getTrackCount(); i++) {
                            MediaFormat f = ex.getTrackFormat(i);
                            String mime = f.getString(MediaFormat.KEY_MIME);
                            if (mime != null && mime.startsWith("audio/")) { tr = i; break; }
                        }
                        if (tr < 0) { Log.w(TAG, "No audio track in segment for append: " + seg.getName()); continue; }
                        ex.selectTrack(tr);
                        while (true) {
                            info.offset = 0;
                            info.size = ex.readSampleData(buffer, 0);
                            if (info.size < 0) break;
                            long pts = ex.getSampleTime();
                            if (pts < 0) break;
                            info.presentationTimeUs = pts + ptsOffsetUs;
                            info.flags = ex.getSampleFlags();
                            muxer.writeSampleData(outTrack, buffer, info);
                            ex.advance();
                        }
                        long durUs = getDurationUsFast(seg);
                        if (durUs > 0) ptsOffsetUs += durUs;
                    } finally { try { ex.release(); } catch (Exception ignore) {} }
                }
            }
        } finally {
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignore) {}
            try { if (ex0 != null) ex0.release(); } catch (Exception ignore) {}
        }
    }

    private void buildMergedFromSegments(List<File> segments, File outFile) throws IOException {
        // Ensure parent
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (outFile.exists() && !outFile.delete()) {
            Log.w(TAG, "Failed to delete existing output before merge: " + outFile.getAbsolutePath());
        }
        MediaMuxer muxer = null;
        MediaExtractor extractor = null;
        int audioTrackIndex = -1;
        try {
            // Prepare first segment to get format
            extractor = new MediaExtractor();
            extractor.setDataSource(segments.get(0).getAbsolutePath());
            int inTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    inTrack = i; audioFormat = f; break;
                }
            }
            if (audioFormat == null) throw new IOException("No audio track in first segment");
            muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            audioTrackIndex = muxer.addTrack(audioFormat);
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long ptsOffsetUs = 0;
            // Iterate all segments
            for (int s = 0; s < segments.size(); s++) {
                File seg = segments.get(s);
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(seg.getAbsolutePath());
                    int segTrack = -1;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        MediaFormat f = ex.getTrackFormat(i);
                        String mime = f.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("audio/")) { segTrack = i; break; }
                    }
                    if (segTrack < 0) { Log.w(TAG, "No audio track in segment: " + seg.getName()); continue; }
                    ex.selectTrack(segTrack);
                    while (true) {
                        info.offset = 0;
                        info.size = ex.readSampleData(buffer, 0);
                        if (info.size < 0) break;
                        long pts = ex.getSampleTime();
                        if (pts < 0) break;
                        info.presentationTimeUs = pts + ptsOffsetUs;
                        info.flags = ex.getSampleFlags();
                        muxer.writeSampleData(audioTrackIndex, buffer, info);
                        ex.advance();
                    }
                    // update offset
                    long segDurUs = getDurationUsFast(seg);
                    if (segDurUs > 0) ptsOffsetUs += segDurUs;
                } finally {
                    try { ex.release(); } catch (Exception ignore) {}
                }
            }
        } finally {
            try { if (muxer != null) { muxer.stop(); muxer.release(); } } catch (Exception ignore) {}
            try { if (extractor != null) extractor.release(); } catch (Exception ignore) {}
        }
    }

    private long getDurationUsFast(File file) {
        try {
            MediaExtractor ex = new MediaExtractor();
            try {
                ex.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        if (f.containsKey(MediaFormat.KEY_DURATION)) {
                            return f.getLong(MediaFormat.KEY_DURATION);
                        }
                    }
                }
            } finally { try { ex.release(); } catch (Exception ignore) {} }
        } catch (Exception e) { /* ignore */ }
        return -1;
    }

    // ===== Background Continuous Window Worker =====
    private void startContinuousWindowWorker() {
        if (continuousWindowRunning.get()) return;
        continuousWindowStopRequested.set(false);
        continuousWindowRunning.set(true);
        continuousWindowThread = new Thread(() -> {
            Log.d(TAG, "Continuous window worker started");
            while (!continuousWindowStopRequested.get()) {
                try {
                    performContinuousWindowUpdate();
                    synchronized (continuousWindowLock) {
                        continuousWindowLock.wait(2000); // every ~2s
                    }
                } catch (InterruptedException ie) {
                    // ignore
                } catch (Exception e) {
                    Log.w(TAG, "Continuous window worker error", e);
                }
            }
            // One last update before exit
            try { performContinuousWindowUpdate(); } catch (Exception e) { Log.w(TAG, "Final window update failed", e); }
            continuousWindowRunning.set(false);
            Log.d(TAG, "Continuous window worker stopped");
        }, "ContinuousWindowWorker");
        continuousWindowThread.setDaemon(true);
        continuousWindowThread.start();
    }

    private void stopContinuousWindowWorker() {
        continuousWindowStopRequested.set(true);
        synchronized (continuousWindowLock) {
            continuousWindowLock.notifyAll();
        }
        try {
            if (continuousWindowThread != null) {
                continuousWindowThread.join(1500);
            }
        } catch (InterruptedException ignored) {}
    }

    private void performContinuousWindowUpdate() {
        if (maxDurationMs == null || maxDurationMs <= 0) return;
        File source = continuousOutputFile;
        if (source == null || !source.exists() || source.length() < 1024) return;
        if (continuousWindowTempFile == null) return;
        try {
            long durMs = getAudioFileDuration(source);
            double durSec = durMs / 1000.0;
            double targetSec = maxDurationMs / 1000.0;
            File work = new File(continuousWindowTempFile.getParentFile(), ".cw_work_" + System.currentTimeMillis() + ".m4a");
            boolean ok;
            if (durSec > targetSec) {
                double startSec = Math.max(0.0, durSec - targetSec);
                ok = fastTrimFile(source, work, startSec, durSec);
                if (!ok) {
                    // Fallback to slower trim, still in background thread
                    AudioFileProcessor.trimAudioFile(source, work, startSec, durSec);
                    ok = true;
                }
            } else {
                // Duration <= window: best-effort copy entire file as current window snapshot
                ok = fastTrimFile(source, work, 0.0, Math.max(0.001, durSec));
                if (!ok) {
                    // If fastTrim can't handle 0..dur, just copy via merge single file
                    buildMergedFromSegments(java.util.Arrays.asList(source), work);
                    ok = true;
                }
            }
            if (ok) {
                if (continuousWindowTempFile.exists() && !continuousWindowTempFile.delete()) {
                    Log.w(TAG, "Failed deleting old continuous window temp");
                }
                if (!work.renameTo(continuousWindowTempFile)) {
                    // fallback: rebuild directly
                    buildMergedFromSegments(java.util.Arrays.asList(source), continuousWindowTempFile);
                    if (work.exists()) work.delete();
                }
            } else {
                if (work.exists()) work.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "performContinuousWindowUpdate failed", e);
        }
    }

}