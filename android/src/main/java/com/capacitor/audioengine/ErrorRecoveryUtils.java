package com.capacitor.audioengine;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/**
 * Utility class for error recovery strategies in segment rolling recording
 */
public class ErrorRecoveryUtils {
    private static final String TAG = "ErrorRecovery";

    /**
     * Recovery strategy for merge failures
     * @param segments List of segments to merge
     * @param primaryError The original error that occurred
     * @return Recovery result with file or alternative strategy
     */
    public static RecoveryResult attemptMergeRecovery(List<File> segments, Exception primaryError) {
        Log.w(TAG, "Attempting merge recovery after error: " + primaryError.getMessage());

        if (segments == null || segments.isEmpty()) {
            return RecoveryResult.failure("No segments available for recovery");
        }

        // Strategy 1: Return the most recent valid segment
        File mostRecentSegment = findMostRecentValidSegment(segments);
        if (mostRecentSegment != null) {
            Log.d(TAG, "Recovery strategy 1: Using most recent segment: " + mostRecentSegment.getName());
            return RecoveryResult.success(mostRecentSegment, "Used most recent segment as fallback");
        }

        // Strategy 2: Try to merge only the last few segments
        if (segments.size() > 3) {
            List<File> recentSegments = segments.subList(Math.max(0, segments.size() - 3), segments.size());
            try {
                File recoveredFile = attemptSimpleMerge(recentSegments);
                if (recoveredFile != null) {
                    Log.d(TAG, "Recovery strategy 2: Simple merge of " + recentSegments.size() + " segments succeeded");
                    return RecoveryResult.success(recoveredFile, "Recovered using simple merge of recent segments");
                }
            } catch (Exception e) {
                Log.w(TAG, "Recovery strategy 2 failed", e);
            }
        }

        // Strategy 3: Create a minimal valid file
        try {
            File minimalFile = createMinimalValidFile(segments.get(0).getParentFile());
            if (minimalFile != null) {
                Log.d(TAG, "Recovery strategy 3: Created minimal valid file");
                return RecoveryResult.success(minimalFile, "Created minimal fallback file");
            }
        } catch (Exception e) {
            Log.w(TAG, "Recovery strategy 3 failed", e);
        }

        return RecoveryResult.failure("All recovery strategies failed");
    }

    /**
     * Find the most recent valid segment from a list
     * @param segments List of segment files
     * @return Most recent valid segment or null
     */
    private static File findMostRecentValidSegment(List<File> segments) {
        // Sort by last modified time (most recent first)
        List<File> sortedSegments = new ArrayList<>(segments);
        sortedSegments.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File segment : sortedSegments) {
            if (isValidSegment(segment)) {
                return segment;
            }
        }

        return null;
    }

    /**
     * Attempt a simple file concatenation merge
     * @param segments Segments to merge
     * @return Merged file or null if failed
     */
    private static File attemptSimpleMerge(List<File> segments) throws IOException {
        // This is a simplified merge that just concatenates the file data
        // Not ideal for audio but better than nothing in recovery scenarios

        File outputFile = new File(segments.get(0).getParentFile(), "recovery_merged_" + System.currentTimeMillis() + ".m4a");

        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile)) {
            for (File segment : segments) {
                if (isValidSegment(segment)) {
                    try (java.io.FileInputStream fis = new java.io.FileInputStream(segment)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }
            }
            fos.flush();
        }

        return outputFile.exists() && outputFile.length() > 0 ? outputFile : null;
    }

    /**
     * Create a minimal valid audio file as last resort
     * @param directory Directory to create file in
     * @return Minimal file or null if failed
     */
    private static File createMinimalValidFile(File directory) {
        // Copy the smallest existing segment as a template
        File[] existingFiles = directory.listFiles((dir, name) ->
            name.startsWith("segment_") && name.endsWith(".m4a"));

        if (existingFiles != null && existingFiles.length > 0) {
            // Find the smallest file to minimize impact
            File smallestFile = existingFiles[0];
            for (File file : existingFiles) {
                if (file.length() < smallestFile.length() && file.length() > 1024) {
                    smallestFile = file;
                }
            }

            try {
                File minimalFile = new File(directory, "minimal_recovery_" + System.currentTimeMillis() + ".m4a");
                java.nio.file.Files.copy(smallestFile.toPath(), minimalFile.toPath());
                return minimalFile;
            } catch (Exception e) {
                Log.w(TAG, "Failed to create minimal file", e);
            }
        }

        return null;
    }

    /**
     * Check if a segment file is valid for recovery purposes
     * @param segment Segment file to check
     * @return true if segment appears valid
     */
    private static boolean isValidSegment(File segment) {
        return segment != null &&
               segment.exists() &&
               segment.length() > 1024 && // At least 1KB
               segment.canRead();
    }

    /**
     * Recovery strategy for recording failures
     * @param error The recording error that occurred
     * @param segmentIndex Current segment index
     * @return Recovery action to take
     */
    public static RecordingRecoveryAction getRecordingRecoveryAction(Exception error, int segmentIndex) {
        String errorMessage = error.getMessage();

        // Storage full errors
        if (errorMessage != null && (errorMessage.contains("No space left") ||
                                   errorMessage.contains("ENOSPC") ||
                                   errorMessage.contains("storage"))) {
            return RecordingRecoveryAction.STOP_RECORDING("Storage full");
        }

        // Permission errors
        if (errorMessage != null && (errorMessage.contains("permission") ||
                                   errorMessage.contains("EPERM"))) {
            return RecordingRecoveryAction.STOP_RECORDING("Permission denied");
        }

        // Device busy errors (might be temporary)
        if (errorMessage != null && (errorMessage.contains("busy") ||
                                   errorMessage.contains("EBUSY"))) {
            if (segmentIndex < 3) { // Retry for first few segments
                return RecordingRecoveryAction.RETRY_SEGMENT("Device busy - retrying");
            } else {
                return RecordingRecoveryAction.STOP_RECORDING("Device consistently busy");
            }
        }

        // Generic MediaRecorder errors
        if (error instanceof RuntimeException && segmentIndex < 5) {
            return RecordingRecoveryAction.RETRY_SEGMENT("MediaRecorder error - retrying");
        }

        // Default: stop recording for unknown errors after a few attempts
        if (segmentIndex < 2) {
            return RecordingRecoveryAction.RETRY_SEGMENT("Unknown error - retrying");
        } else {
            return RecordingRecoveryAction.STOP_RECORDING("Persistent errors detected");
        }
    }

    /**
     * Result of a recovery attempt
     */
    public static class RecoveryResult {
        private final boolean success;
        private final File recoveredFile;
        private final String message;

        private RecoveryResult(boolean success, File recoveredFile, String message) {
            this.success = success;
            this.recoveredFile = recoveredFile;
            this.message = message;
        }

        public static RecoveryResult success(File file, String message) {
            return new RecoveryResult(true, file, message);
        }

        public static RecoveryResult failure(String message) {
            return new RecoveryResult(false, null, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public File getRecoveredFile() {
            return recoveredFile;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            return "RecoveryResult{" +
                   "success=" + success +
                   ", file=" + (recoveredFile != null ? recoveredFile.getName() : "null") +
                   ", message='" + message + '\'' +
                   '}';
        }
    }

    /**
     * Recovery action for recording errors
     */
    public static class RecordingRecoveryAction {
        public enum Action {
            RETRY_SEGMENT, STOP_RECORDING
        }

        private final Action action;
        private final String reason;

        private RecordingRecoveryAction(Action action, String reason) {
            this.action = action;
            this.reason = reason;
        }

        public static RecordingRecoveryAction RETRY_SEGMENT(String reason) {
            return new RecordingRecoveryAction(Action.RETRY_SEGMENT, reason);
        }

        public static RecordingRecoveryAction STOP_RECORDING(String reason) {
            return new RecordingRecoveryAction(Action.STOP_RECORDING, reason);
        }

        public Action getAction() {
            return action;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public String toString() {
            return "RecordingRecoveryAction{" +
                   "action=" + action +
                   ", reason='" + reason + '\'' +
                   '}';
        }
    }
}