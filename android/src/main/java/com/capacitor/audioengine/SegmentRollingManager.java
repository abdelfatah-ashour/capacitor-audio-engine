package com.capacitor.audioengine;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Refactored SegmentRollingManager with simplified architecture
 * Key improvements:
 * - Uses Deque<File> for rolling window instead of complex queue system
 * - Single-threaded executor for background pre-merge
 * - Always-ready premerged.m4a file for instant stop
 * - Optimized 20-30s segment lengths
 * - Duration-based rolling: tracks total duration and drops segments to stay under KEEP_DURATION
 */
public class SegmentRollingManager implements AudioInterruptionManager.InterruptionCallback {
    private static final String TAG = "SegmentRollingManager";
    private static final String SEGMENT_PREFIX = "segment_";
    private static final String PREMERGED_FILENAME = "premerged.m4a";
    private static final String TEMP_PREMERGED_FILENAME = "temp_premerged.m4a";

    // Core components
    private final EnhancedMediaRecorder enhancedRecorder;
    private final SegmentRollingConfig config;
    private final File segmentsDirectory;
    private final AudioInterruptionManager interruptionManager;

    // Rolling segment management - using Deque for rolling window
    private final Deque<File> segmentDeque = new ArrayDeque<>();
    private final long keepDurationMs; // Duration-based rolling instead of count-based
    private final AtomicInteger segmentIndex = new AtomicInteger(0);

    // Recording state
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private Timer segmentTimer;

    // Pre-merge system - simplified single-threaded executor
    private final ExecutorService mergeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SegmentMerger");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); // Lower priority to not interfere with recording
        return t;
    });

    private File preMergedFile;
    private final AtomicBoolean isMerging = new AtomicBoolean(false);
    private final AtomicLong totalRecordedDurationMs = new AtomicLong(0);

    // Duration tracking
    private long recordingStartTime = 0;
    private DurationChangeCallback durationCallback;
    private Timer durationTimer;

    // Performance and debugging
    private boolean debugMode = false;
    private final AtomicLong totalMergeTime = new AtomicLong(0);
    private final AtomicInteger mergeCount = new AtomicInteger(0);

    public SegmentRollingManager(Context context, SegmentRollingConfig config) throws IOException {
        Log.d(TAG, "=== SegmentRollingManager CONSTRUCTOR (Refactored) ===");

        this.config = config;
        this.segmentsDirectory = new File(config.getOutputDirectory(), "segments");

        // Create segments directory
        if (!segmentsDirectory.exists() && !segmentsDirectory.mkdirs()) {
            throw new IOException("Failed to create segments directory: " + segmentsDirectory.getAbsolutePath());
        }

        // Calculate keep duration from config (duration-based rolling)
        this.keepDurationMs = config.getMaxDurationMs();
        Log.d(TAG, "Duration-based rolling configured: keepDuration=" + keepDurationMs + "ms (" +
              (keepDurationMs / 1000) + "s, segmentLength=" + config.getSegmentLengthMs() + "ms)");

        // Initialize components
        this.enhancedRecorder = new EnhancedMediaRecorder();
        this.interruptionManager = new AudioInterruptionManager(context);
        this.interruptionManager.setInterruptionCallback(this);
        this.preMergedFile = new File(segmentsDirectory, PREMERGED_FILENAME);

        // Clean up any leftover files
        cleanupLeftoverFiles();

        Log.d(TAG, "SegmentRollingManager initialized with duration-based rolling architecture");
        Log.d(TAG, "Segment duration: " + config.getSegmentLengthMs() + "ms");
        Log.d(TAG, "Keep duration: " + (keepDurationMs / 1000) + "s (duration-based rolling)");
        Log.d(TAG, "Pre-merge enabled: " + config.isPreMergeEnabled());
    }

    /**
     * Start segment rolling recording
     */
    public void startSegmentRolling() throws IOException {
        Log.d(TAG, "=== Starting segment rolling ===");

        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }

        // Reset state
        segmentIndex.set(0);
        recordingStartTime = System.currentTimeMillis();
        totalRecordedDurationMs.set(0);
        clearSegmentDeque();

        // Delete old premerged file
        if (preMergedFile.exists()) {
            if (!preMergedFile.delete()) {
                Log.w(TAG, "Failed to delete old premerged file");
            }
        }

        isRecording.set(true);
        isPaused.set(false);

        // Start first segment
        startNewSegment();
        startDurationTimer();
        interruptionManager.startMonitoring();

        Log.d(TAG, "Segment rolling started successfully");
    }

    /**
     * Pause segment rolling
     */
    public void pauseSegmentRolling() {
        if (!isRecording.get() || isPaused.get()) {
            return;
        }

        Log.d(TAG, "Pausing segment rolling");
        isPaused.set(true);
        stopCurrentSegment();
        stopSegmentTimer();
        stopDurationTimer();
    }

    /**
     * Resume segment rolling
     */
    public void resumeSegmentRolling() throws IOException {
        if (!isRecording.get() || !isPaused.get()) {
            return;
        }

        Log.d(TAG, "Resuming segment rolling");
        isPaused.set(false);
        startNewSegment();
        startDurationTimer();
    }

    /**
     * Stop segment rolling - instant operation returns premerged file
     */
    public File stopSegmentRolling() throws IOException {
        Log.d(TAG, "=== Stopping segment rolling ===");
        long stopStartTime = System.currentTimeMillis();

        if (!isRecording.get()) {
            Log.w(TAG, "Not recording");
            return null;
        }

        // Set state to stopped
        isRecording.set(false);
        isPaused.set(false);

        // Stop current segment
        stopCurrentSegment();
        stopSegmentTimer();
        stopDurationTimer();
        interruptionManager.stopMonitoring();

        // Wait briefly for any pending merge to complete
        waitForMergeCompletion(2000); // 2 second timeout

        // Perform final cleanup to ensure we're within duration limits
        performFinalCleanup();

        // Return premerged file if it exists and is valid
        if (preMergedFile.exists() && preMergedFile.length() > 1024) {
            long stopDuration = System.currentTimeMillis() - stopStartTime;
            Log.d(TAG, "✅ INSTANT STOP completed in " + stopDuration + "ms");
            Log.d(TAG, "Returning premerged file: " + preMergedFile.getName() +
                  " (" + (preMergedFile.length() / 1024) + "KB)");

            return preMergedFile;
        }

        // Fallback: direct merge if premerged file not available
        Log.w(TAG, "Premerged file not available, performing direct merge");
        List<File> segments = new ArrayList<>(segmentDeque);

        if (segments.isEmpty()) {
            throw new IOException("No segments available for merging");
        }

        File fallbackFile = new File(segmentsDirectory, "fallback_merged_" + System.currentTimeMillis() + ".m4a");
        MediaMuxerHelper.mergeSegments(segments, fallbackFile);

        long stopDuration = System.currentTimeMillis() - stopStartTime;
        Log.d(TAG, "Fallback merge completed in " + stopDuration + "ms");

        return fallbackFile;
    }

    /**
     * Reset segment rolling
     */
    public void resetSegmentRolling() throws IOException {
        Log.d(TAG, "Resetting segment rolling");

        if (isRecording.get()) {
            stopSegmentRolling();
        }

        clearSegmentDeque();
        segmentIndex.set(0);
        recordingStartTime = 0;
        totalRecordedDurationMs.set(0);

        if (preMergedFile.exists()) {
            preMergedFile.delete();
        }
    }

    /**
     * Shutdown and cleanup resources
     */
    public void shutdown() {
        Log.d(TAG, "Shutting down SegmentRollingManager");

        try {
            if (isRecording.get()) {
                stopSegmentRolling();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording during shutdown", e);
        }

        // Shutdown merge executor
        mergeExecutor.shutdown();
        try {
            if (!mergeExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                mergeExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            mergeExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Cleanup resources
        try {
            enhancedRecorder.release();
        } catch (Exception e) {
            Log.e(TAG, "Error releasing recorder", e);
        }

        interruptionManager.stopMonitoring();

        if (debugMode) {
            logPerformanceStats();
        }
    }

    // ===== SEGMENT MANAGEMENT =====

    /**
     * Start a new segment
     */
    private void startNewSegment() throws IOException {
        stopCurrentSegment();

        int index = segmentIndex.incrementAndGet();
        File segmentFile = new File(segmentsDirectory, SEGMENT_PREFIX + index + ".m4a");

        Log.d(TAG, "Starting segment " + index + ": " + segmentFile.getName());

        try {
            enhancedRecorder.configureAndStart(config.getAudioConfig(), segmentFile);
            startSegmentTimer();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start segment " + index, e);
            throw new IOException("Failed to start segment: " + e.getMessage(), e);
        }
    }

    /**
     * Stop current segment and add to rolling buffer
     */
    private void stopCurrentSegment() {
        if (!enhancedRecorder.isRecording()) {
            return;
        }

        try {
            File segmentFile = enhancedRecorder.getCurrentOutputFile();
            File stoppedFile = enhancedRecorder.stopSafely();

            if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                addSegmentToRollingBuffer(stoppedFile);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping current segment", e);
        }

        stopSegmentTimer();
    }

    /**
     * Add segment to rolling buffer with duration-based cleanup
     */
    private synchronized void addSegmentToRollingBuffer(File segmentFile) {
        Log.d(TAG, "Adding segment to buffer: " + segmentFile.getName() +
              " (current duration: " + (totalRecordedDurationMs.get() / 1000) + "s/" + (keepDurationMs / 1000) + "s)");

        // Validate segment
        if (!MediaMuxerHelper.isValidAudioFile(segmentFile)) {
            Log.w(TAG, "Invalid segment file, skipping: " + segmentFile.getName());
            return;
        }

        // Add new segment
        segmentDeque.addLast(segmentFile);

        // Update total duration
        long segmentDuration = getSegmentDuration(segmentFile);
        totalRecordedDurationMs.addAndGet(segmentDuration);

        // Check if this is likely a partial segment (less than 50% of expected segment length)
        boolean isPartialSegment = segmentDuration < (config.getSegmentLengthMs() / 2);

        Log.d(TAG, "Segment analysis: " + segmentFile.getName() +
              " duration=" + (segmentDuration / 1000) + "s" +
              " (expected=" + (config.getSegmentLengthMs() / 1000) + "s)" +
              " partial=" + isPartialSegment);

        // Duration-based rolling: different logic for ongoing recording vs final cleanup
        long toleranceBuffer = config.getSegmentLengthMs() / 2; // Smaller tolerance: 0.5x segment length
        long effectiveLimit = keepDurationMs + toleranceBuffer;

        // For partial segments (like stop-recording segments), always enforce the strict limit
        // For full segments during recording, use tolerance buffer
        boolean shouldEnforceStrictLimit = isPartialSegment;
        long limitToUse = shouldEnforceStrictLimit ? keepDurationMs : effectiveLimit;

        if (totalRecordedDurationMs.get() > limitToUse && segmentDeque.size() > 1) {
            Log.d(TAG, "Duration exceeds limit: " + (totalRecordedDurationMs.get() / 1000) + "s > " +
                  (limitToUse / 1000) + "s" +
                  (shouldEnforceStrictLimit ? " (strict limit for partial segment)" : " (tolerance limit)") +
                  ", starting removal");

            while (totalRecordedDurationMs.get() > keepDurationMs && segmentDeque.size() > 1) {
                File oldestSegment = segmentDeque.removeFirst();
                long oldDuration = getSegmentDuration(oldestSegment);
                totalRecordedDurationMs.addAndGet(-oldDuration);

                Log.d(TAG, "Removing oldest segment for duration limit: " + oldestSegment.getName() +
                      " (duration: " + (oldDuration / 1000) + "s, remaining: " + (totalRecordedDurationMs.get() / 1000) + "s)");

                // Delete old file immediately to free storage
                if (!oldestSegment.delete()) {
                    Log.w(TAG, "Failed to delete old segment: " + oldestSegment.getName());
                }
            }
        } else {
            Log.d(TAG, "No rolling needed: totalDuration=" + (totalRecordedDurationMs.get() / 1000) + "s" +
                  ", limit=" + (limitToUse / 1000) + "s" +
                  " (" + (shouldEnforceStrictLimit ? "strict" : "tolerance") + ")" +
                  ", partial=" + isPartialSegment +
                  ", segments=" + segmentDeque.size());
        }

        Log.d(TAG, "Buffer updated - segments: " + segmentDeque.size() +
              ", total duration: " + (totalRecordedDurationMs.get() / 1000) + "s/" + (keepDurationMs / 1000) + "s");

        // Trigger background pre-merge
        if (config.isPreMergeEnabled()) {
            triggerBackgroundMerge();
        }
    }

    /**
     * Clear all segments from deque
     */
    private synchronized void clearSegmentDeque() {
        Log.d(TAG, "Clearing segment deque (" + segmentDeque.size() + " segments)");

        // Delete all segment files
        for (File segment : segmentDeque) {
            if (segment.exists() && !segment.delete()) {
                Log.w(TAG, "Failed to delete segment: " + segment.getName());
            }
        }

        segmentDeque.clear();
        totalRecordedDurationMs.set(0);
    }

    /**
     * Perform final cleanup when stopping recording to ensure duration limits
     */
    private synchronized void performFinalCleanup() {
        Log.d(TAG, "Performing final cleanup - current duration: " + (totalRecordedDurationMs.get() / 1000) + "s, limit: " + (keepDurationMs / 1000) + "s");

        if (totalRecordedDurationMs.get() <= keepDurationMs) {
            Log.d(TAG, "Final cleanup: already within limit, no action needed");
            return;
        }

        // Strategy: Keep the most recent audio that fits within the limit
        // Work backwards from the newest segments
        List<File> segmentsToKeep = new ArrayList<>();
        long runningDuration = 0;

        // Process segments from newest to oldest
        List<File> allSegments = new ArrayList<>(segmentDeque);
        for (int i = allSegments.size() - 1; i >= 0; i--) {
            File segment = allSegments.get(i);
            long segmentDuration = getSegmentDuration(segment);

            if (runningDuration + segmentDuration <= keepDurationMs) {
                segmentsToKeep.add(0, segment); // Add to front to maintain order
                runningDuration += segmentDuration;
                Log.d(TAG, "Final cleanup: keeping " + segment.getName() +
                      " (" + (segmentDuration / 1000) + "s), total: " + (runningDuration / 1000) + "s");
            } else {
                Log.d(TAG, "Final cleanup: removing " + segment.getName() +
                      " (" + (segmentDuration / 1000) + "s) - would exceed limit");

                // Delete the segment we're not keeping
                if (!segment.delete()) {
                    Log.w(TAG, "Failed to delete segment during final cleanup: " + segment.getName());
                }
            }
        }

        // Update the deque with only the segments we're keeping
        segmentDeque.clear();
        segmentDeque.addAll(segmentsToKeep);
        totalRecordedDurationMs.set(runningDuration);

        // Trigger final merge with cleaned up segments
        if (config.isPreMergeEnabled() && !segmentDeque.isEmpty()) {
            Log.d(TAG, "Triggering final merge after cleanup");
            triggerBackgroundMerge();
            waitForMergeCompletion(3000); // Wait a bit longer for final merge
        }

        Log.d(TAG, "Final cleanup completed - final duration: " + (totalRecordedDurationMs.get() / 1000) + "s, segments: " + segmentDeque.size());
    }

    // ===== BACKGROUND PRE-MERGE =====

    /**
     * Trigger background merge of current segments
     */
    private void triggerBackgroundMerge() {
        if (isMerging.get()) {
            if (debugMode) {
                Log.d(TAG, "Merge already in progress, skipping");
            }
            return;
        }

        // Create snapshot of current segments
        List<File> segmentsSnapshot;
        synchronized (this) {
            segmentsSnapshot = new ArrayList<>(segmentDeque);
        }

        if (segmentsSnapshot.isEmpty()) {
            return;
        }

        // Submit merge task
        mergeExecutor.submit(() -> performBackgroundMerge(segmentsSnapshot));
    }

    /**
     * Perform background merge with error handling
     */
    private void performBackgroundMerge(List<File> segments) {
        if (!isMerging.compareAndSet(false, true)) {
            return; // Another merge is already running
        }

        long mergeStartTime = System.currentTimeMillis();

        try {
            Log.d(TAG, "Starting background merge of " + segments.size() + " segments");

            // Create temporary file for atomic swap
            File tempFile = new File(segmentsDirectory, TEMP_PREMERGED_FILENAME);
            if (tempFile.exists()) {
                tempFile.delete();
            }

            // Merge segments
            MediaMuxerHelper.mergeSegments(segments, tempFile);

            // Atomic swap: rename temp file to final premerged file
            if (preMergedFile.exists()) {
                preMergedFile.delete();
            }

            if (tempFile.renameTo(preMergedFile)) {
                long mergeTime = System.currentTimeMillis() - mergeStartTime;
                totalMergeTime.addAndGet(mergeTime);
                mergeCount.incrementAndGet();

                Log.d(TAG, "✅ Background merge completed in " + mergeTime + "ms");
                Log.d(TAG, "Premerged file: " + preMergedFile.getName() +
                      " (" + (preMergedFile.length() / 1024) + "KB)");
            } else {
                Log.e(TAG, "Failed to rename temp file to premerged file");
                // Clean up temp file
                tempFile.delete();
            }

        } catch (Exception e) {
            Log.e(TAG, "Background merge failed", e);

            // Clean up temp file on error
            File tempFile = new File(segmentsDirectory, TEMP_PREMERGED_FILENAME);
            if (tempFile.exists()) {
                tempFile.delete();
            }
        } finally {
            isMerging.set(false);
        }
    }

    /**
     * Wait for merge completion with timeout
     */
    private void waitForMergeCompletion(long timeoutMs) {
        if (!isMerging.get()) {
            return;
        }

        Log.d(TAG, "Waiting for merge completion...");
        long startTime = System.currentTimeMillis();

        while (isMerging.get() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (isMerging.get()) {
            Log.w(TAG, "Merge did not complete within timeout");
        }
    }

    // ===== TIMER MANAGEMENT =====

    private void startSegmentTimer() {
        stopSegmentTimer();

        segmentTimer = new Timer("SegmentTimer", true);
        segmentTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (isRecording.get() && !isPaused.get()) {
                    try {
                        startNewSegment();
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to start new segment", e);
                    }
                }
            }
        }, config.getSegmentLengthMs());
    }

    private void stopSegmentTimer() {
        if (segmentTimer != null) {
            segmentTimer.cancel();
            segmentTimer = null;
        }
    }

    private void startDurationTimer() {
        stopDurationTimer();

        if (durationCallback == null) {
            return;
        }

        durationTimer = new Timer("DurationTimer", true);
        durationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (durationCallback != null) {
                    long elapsed = getElapsedRecordingTime();
                    durationCallback.onDurationChanged(elapsed);
                }
            }
        }, 1000, 1000); // Update every second
    }

    private void stopDurationTimer() {
        if (durationTimer != null) {
            durationTimer.cancel();
            durationTimer = null;
        }
    }

    // ===== UTILITY METHODS =====

    private long getSegmentDuration(File segmentFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(segmentFile.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return durationStr != null ? Long.parseLong(durationStr) : config.getSegmentLengthMs();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get duration for " + segmentFile.getName() + ", using config duration");
            return config.getSegmentLengthMs();
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing metadata retriever", e);
                }
            }
        }
    }

    private void cleanupLeftoverFiles() {
        if (!segmentsDirectory.exists()) {
            return;
        }

        File[] files = segmentsDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.getName().startsWith(SEGMENT_PREFIX) ||
                    file.getName().equals(PREMERGED_FILENAME) ||
                    file.getName().equals(TEMP_PREMERGED_FILENAME)) {

                    if (!file.delete()) {
                        Log.w(TAG, "Failed to delete leftover file: " + file.getName());
                    }
                }
            }
        }
    }

    private void logPerformanceStats() {
        Log.d(TAG, "=== Performance Stats ===");
        Log.d(TAG, "Total merges: " + mergeCount.get());
        Log.d(TAG, "Total merge time: " + totalMergeTime.get() + "ms");
        if (mergeCount.get() > 0) {
            Log.d(TAG, "Average merge time: " + (totalMergeTime.get() / mergeCount.get()) + "ms");
        }
        Log.d(TAG, "Current segments: " + segmentDeque.size());
        Log.d(TAG, "Total buffered duration: " + (totalRecordedDurationMs.get() / 1000) + "s");
        Log.d(TAG, "Keep duration limit: " + (keepDurationMs / 1000) + "s");
        Log.d(TAG, "Duration utilization: " + String.format("%.1f%%", (totalRecordedDurationMs.get() * 100.0 / keepDurationMs)));
    }

    // ===== PUBLIC API =====

    public boolean isSegmentRollingActive() {
        return isRecording.get();
    }

    public long getElapsedRecordingTime() {
        if (recordingStartTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - recordingStartTime;
    }

    public void setDurationChangeCallback(DurationChangeCallback callback) {
        this.durationCallback = callback;
    }

    public void setDebugMode(boolean enabled) {
        this.debugMode = enabled;
        Log.d(TAG, "Debug mode " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * Get detailed rolling buffer state for debugging
     */
    public String getRollingBufferState() {
        synchronized (this) {
            StringBuilder sb = new StringBuilder();
            sb.append("Rolling Buffer State:\n");
            sb.append("  Keep Duration: ").append(keepDurationMs / 1000).append("s\n");
            sb.append("  Current Total: ").append(totalRecordedDurationMs.get() / 1000).append("s\n");
            sb.append("  Segments: ").append(segmentDeque.size()).append("\n");

            int i = 1;
            for (File segment : segmentDeque) {
                long duration = getSegmentDuration(segment);
                sb.append("    ").append(i++).append(". ").append(segment.getName())
                  .append(" (").append(duration / 1000).append("s)\n");
            }

            return sb.toString();
        }
    }

    public List<File> getCurrentSegments() {
        synchronized (this) {
            return new ArrayList<>(segmentDeque);
        }
    }

    public long getBufferedDuration() {
        return totalRecordedDurationMs.get();
    }

    public String getDebugInfo() {
        return "SegmentRollingManager{" +
               "isRecording=" + isRecording.get() +
               ", isPaused=" + isPaused.get() +
               ", segmentCount=" + segmentDeque.size() +
               ", bufferedDurationMs=" + totalRecordedDurationMs.get() +
               ", keepDurationMs=" + keepDurationMs +
               ", durationUtilization=" + String.format("%.1f%%", (totalRecordedDurationMs.get() * 100.0 / keepDurationMs)) +
               ", isMerging=" + isMerging.get() +
               ", mergeCount=" + mergeCount.get() +
               ", avgMergeTime=" + (mergeCount.get() > 0 ? totalMergeTime.get() / mergeCount.get() : 0) + "ms" +
               '}';
    }

    // ===== AUDIO INTERRUPTION CALLBACKS =====

    @Override
    public void onInterruptionBegan(AudioInterruptionManager.InterruptionType type) {
        Log.d(TAG, "Audio interruption began: " + type);
        if (isRecording.get() && !isPaused.get()) {
            try {
                pauseSegmentRolling();
            } catch (Exception e) {
                Log.e(TAG, "Error pausing on interruption", e);
            }
        }
    }

    @Override
    public void onInterruptionEnded(AudioInterruptionManager.InterruptionType type, boolean shouldResume) {
        Log.d(TAG, "Audio interruption ended: " + type + ", shouldResume: " + shouldResume);
        if (isRecording.get() && isPaused.get() && shouldResume) {
            try {
                resumeSegmentRolling();
            } catch (IOException e) {
                Log.e(TAG, "Error resuming after interruption", e);
            }
        }
    }

    @Override
    public void onAudioRouteChanged(String reason) {
        Log.d(TAG, "Audio route changed: " + reason);
        if ("headphone_disconnect".equals(reason) && isRecording.get()) {
            try {
                pauseSegmentRolling();
            } catch (Exception e) {
                Log.e(TAG, "Error pausing on audio route change", e);
            }
        }
    }

    /**
     * Functional interface for duration change callbacks
     */
    public interface DurationChangeCallback {
        void onDurationChanged(long durationMs);
    }
}