package com.capacitor.audioengine;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Manages rolling compressed (AAC/M4A) segment recording and fast final merge.
 * Uses MediaRecorderWrapper to produce 1-minute M4A chunks and retains only the
 * last N files that cover the configured maxDuration seconds.
 */
public class RollingRecordingManager {
    private static final String TAG = "RollingRecordingManager";

    // Segment length in seconds (1 minute)
    private static final int SEGMENT_SECONDS = 60;

    private final Context context;
    private final FileDirectoryManager fileManager;
    private final AudioRecordingConfig recordingConfig;
    private final int maxDurationSeconds; // total rolling window

    private final HandlerThread workerThread;
    private final Handler workerHandler;

    // Keep completed segments only; do not include the currently-active segment here
    private final Deque<File> completedSegments = new ArrayDeque<>();
    private MediaRecorderWrapper recorder;
    private File currentSegmentFile;
    private boolean active = false;

    private long segmentStartMs = 0;

    // Rolling merged file state
    private File rollingMergedFile;
    private double mergedDurationSec = 0.0;

    public RollingRecordingManager(Context context,
                                   FileDirectoryManager fileManager,
                                   AudioRecordingConfig recordingConfig,
                                   int maxDurationSeconds) {
        this.context = context;
        this.fileManager = fileManager;
        this.recordingConfig = recordingConfig;
        this.maxDurationSeconds = Math.max(SEGMENT_SECONDS, maxDurationSeconds);

        this.workerThread = new HandlerThread("RollingRecordingWorker");
        this.workerThread.start();
        this.workerHandler = new Handler(workerThread.getLooper());
    }

    public boolean isActive() {
        return active;
    }

    public void start() throws IOException {
        if (active) return;
        active = true;
        recorder = new MediaRecorderWrapper();
        startNewSegment();
        scheduleNextRotation();
        Log.d(TAG, "Rolling recording started");
    }

    /**
     * Pause recording due to an interruption: finalize current active segment and stop scheduling.
     */
    public void pauseForInterruption() {
        if (!active) return;
        active = false;
        try {
            workerHandler.removeCallbacksAndMessages(null);
        } catch (Exception ignored) {}
        try {
            if (recorder != null && recorder.isRecording()) {
                try { recorder.stopSafely(); } catch (Exception e) { Log.w(TAG, "Error stopping on pause", e); }
            }
            if (currentSegmentFile != null && currentSegmentFile.exists()) {
                completedSegments.addLast(currentSegmentFile);
            }
        } catch (Exception e) {
            Log.w(TAG, "pauseForInterruption error", e);
        }
    }

    /**
     * Resume recording after an interruption: start a fresh segment and resume rotation.
     */
    public void resumeAfterInterruption() throws IOException {
        if (active) return;
        recorder = new MediaRecorderWrapper();
        startNewSegment();
        active = true;
        scheduleNextRotation();
        Log.d(TAG, "Rolling recording resumed after interruption");
    }

    private void startNewSegment() throws IOException {
        File dir = ensureSegmentsDirectory();
        currentSegmentFile = new File(dir, "seg_" + System.currentTimeMillis() + ".m4a");
        recorder.configureAndStart(recordingConfig, currentSegmentFile);
        segmentStartMs = System.currentTimeMillis();
        // Do NOT add active segment to completed list; retention counts completed only
        Log.d(TAG, "Started new segment: " + currentSegmentFile.getName());
    }

    private void rotateSegment() {
        if (!active) return;
        workerHandler.post(() -> {
            try {
                // Close current -> becomes a completed segment
                if (recorder != null && recorder.isRecording()) {
                    try {
                        recorder.stopSafely();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping segment", e);
                    }
                }
                if (currentSegmentFile != null && currentSegmentFile.exists()) {
                    completedSegments.addLast(currentSegmentFile);
                    // Append completed segment to rolling merged file
                    appendToMerged(currentSegmentFile);
                    enforceRetentionWindow();
                }
                // Start next
                startNewSegment();
                scheduleNextRotation();
            } catch (Exception e) {
                Log.e(TAG, "Failed to rotate segment", e);
            }
        });
    }

    private void scheduleNextRotation() {
        long elapsed = Math.max(0, System.currentTimeMillis() - segmentStartMs);
        long delayMs = Math.max(0, SEGMENT_SECONDS * 1000L - elapsed);
        workerHandler.postDelayed(this::rotateSegment, delayMs);
    }

    private File ensureSegmentsDirectory() throws IOException {
        File base = fileManager.getRecordingsDirectory();
        File segDir = new File(base, "segments");
        if (!segDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            segDir.mkdirs();
        }
        return segDir;
    }

    private void enforceRetentionWindow() {
        int segmentsToKeep = (int)Math.ceil(maxDurationSeconds / (double)SEGMENT_SECONDS);
        // Retention must apply to completed segments only (exclude active)
        while (completedSegments.size() > segmentsToKeep) {
            File old = completedSegments.pollFirst();
            if (old != null && old.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                Log.d(TAG, "Deleted old segment: " + old.getName());
            }
        }

        // Also enforce max duration on merged file by trimming head overflow
        if (mergedDurationSec > maxDurationSeconds && rollingMergedFile != null && rollingMergedFile.exists()) {
            double overflow = mergedDurationSec - maxDurationSeconds;
            try {
                File dir = ensureSegmentsDirectory();
                File trimmed = new File(dir, "merged_trim_" + System.currentTimeMillis() + ".m4a");
                long trimStartNs = System.nanoTime();
                AudioFileProcessor.trimAudioFile(rollingMergedFile, trimmed, overflow, AudioFileProcessor.getAudioDuration(rollingMergedFile.getAbsolutePath()));
                long trimEndNs = System.nanoTime();
                Log.i(TAG, "[Rolling] Head trim time: " + ((trimEndNs - trimStartNs) / 1_000_000) + " ms, overflow: " + overflow + "s");
                // Replace merged file
                //noinspection ResultOfMethodCallIgnored
                rollingMergedFile.delete();
                if (!trimmed.renameTo(rollingMergedFile)) {
                    // Fallback copy
                    AudioFileProcessor.concatenateAudioFiles(java.util.Arrays.asList(trimmed), rollingMergedFile);
                    //noinspection ResultOfMethodCallIgnored
                    trimmed.delete();
                }
                mergedDurationSec = Math.min(mergedDurationSec, maxDurationSeconds);
            } catch (Exception e) {
                Log.w(TAG, "Failed to head-trim merged file", e);
            }
        }
    }

    /**
     * Stop recording and return a finalized single M4A file assembled from segments.
     */
    public File stopAndAssembleFinal() throws IOException {
        long assembleStartNs = System.nanoTime();
        active = false;

        // Stop current segment immediately
        if (recorder != null) {
            try {
                if (recorder.isRecording()) {
                    recorder.stopSafely();
                }
                recorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping/releasing recorder", e);
            }
            recorder = null;
        }

        // Finalize the last active segment as completed (do NOT enforce retention yet)
        if (currentSegmentFile != null && currentSegmentFile.exists()) {
            completedSegments.addLast(currentSegmentFile);
            appendToMerged(currentSegmentFile);
        }
        // Ensure merged file fits the window (head trim if overflow)
        enforceRetentionWindow();

        // If we have a merged file ready, just move/copy it to final name and return
        if (rollingMergedFile != null && rollingMergedFile.exists() && rollingMergedFile.length() > 0) {
            File outDir = fileManager.getRecordingsDirectory();
            File finalFile = new File(outDir, "recording_" + System.currentTimeMillis() + ".m4a");
            long concatStartNs = System.nanoTime();
            // Fast path: single-file copy via concat helper (or simple rename when same FS)
            boolean renamed = rollingMergedFile.renameTo(finalFile);
            if (!renamed) {
                AudioFileProcessor.concatenateAudioFiles(java.util.Arrays.asList(rollingMergedFile), finalFile);
            }
            long concatEndNs = System.nanoTime();
            Log.i(TAG, "[Rolling] Finalize from merged time: " + ((concatEndNs - concatStartNs) / 1_000_000) + " ms");

            long assembleEndNs = System.nanoTime();
            Log.i(TAG, "[Rolling] Total assemble time: " + ((assembleEndNs - assembleStartNs) / 1_000_000) + " ms");
            Log.d(TAG, "Assembled final file from merged -> " + finalFile.getName());
            return finalFile;
        }

        throw new IOException("Merged file not available at stop");
    }

    private void appendToMerged(File completedSegment) {
        try {
            if (rollingMergedFile == null) {
                File dir = ensureSegmentsDirectory();
                rollingMergedFile = new File(dir, "rollingMerged.m4a");
            }

            File out = new File(rollingMergedFile.getParentFile(), "rollingMerged_tmp.m4a");
            long appendStartNs = System.nanoTime();
            AudioFileProcessor.appendAudioFiles(rollingMergedFile, completedSegment, out);
            long appendEndNs = System.nanoTime();
            Log.i(TAG, "[Rolling] Append time: " + ((appendEndNs - appendStartNs) / 1_000_000) + " ms");

            // Replace original merged
            //noinspection ResultOfMethodCallIgnored
            rollingMergedFile.delete();
            if (!out.renameTo(rollingMergedFile)) {
                // Fallback: write single-file concat to target and remove tmp
                AudioFileProcessor.concatenateAudioFiles(java.util.Arrays.asList(out), rollingMergedFile);
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }

            double segDur = AudioFileProcessor.getAudioDuration(completedSegment.getAbsolutePath());
            if (segDur <= 0) segDur = SEGMENT_SECONDS;
            mergedDurationSec += segDur;
        } catch (Exception e) {
            Log.w(TAG, "Failed to append to merged file", e);
        }
    }

    public void cleanup() {
        try {
            active = false;
            if (recorder != null) {
                try {
                    if (recorder.isRecording()) {
                        recorder.stopSafely();
                    }
                } catch (Exception ignored) {}
                try {
                    recorder.release();
                } catch (Exception ignored) {}
                recorder = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "Cleanup error", e);
        } finally {
            try {
                workerThread.quitSafely();
            } catch (Exception ignored) {}
        }
    }
}


