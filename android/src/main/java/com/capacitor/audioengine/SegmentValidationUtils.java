package com.capacitor.audioengine;

import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;

/**
 * Utility class for validating audio segments and recording operations
 */
public class SegmentValidationUtils {
    private static final String TAG = "SegmentValidation";

    // Validation thresholds
    private static final long MIN_VALID_SEGMENT_SIZE = 1024; // 1KB minimum
    private static final long MIN_VALID_DURATION_MS = 100; // 100ms minimum
    private static final long MAX_DURATION_VARIANCE_MS = 1500; // 1.5s tolerance for segment duration

    /**
     * Validate recording duration against expected duration
     * @param segmentFile The segment file to validate
     * @param expectedDurationMs Expected duration in milliseconds
     * @return ValidationResult with details
     */
    public static ValidationResult validateRecordingDuration(File segmentFile, long expectedDurationMs) {
        if (segmentFile == null || !segmentFile.exists()) {
            return ValidationResult.invalid("Segment file does not exist");
        }

        if (segmentFile.length() < MIN_VALID_SEGMENT_SIZE) {
            return ValidationResult.invalid("Segment file too small: " + segmentFile.length() + " bytes");
        }

        long actualDuration = getActualAudioDuration(segmentFile);
        if (actualDuration <= 0) {
            return ValidationResult.invalid("Could not determine audio duration");
        }

        if (actualDuration < MIN_VALID_DURATION_MS) {
            return ValidationResult.invalid("Audio duration too short: " + actualDuration + "ms");
        }

        long durationDiff = Math.abs(actualDuration - expectedDurationMs);
        if (durationDiff > MAX_DURATION_VARIANCE_MS) {
            String message = String.format("Duration mismatch: expected=%dms, actual=%dms, diff=%dms",
                expectedDurationMs, actualDuration, durationDiff);

            if (durationDiff > expectedDurationMs * 0.5) { // More than 50% off
                return ValidationResult.invalid(message);
            } else {
                return ValidationResult.warning(message);
            }
        }

        return ValidationResult.valid("Duration validation passed: " + actualDuration + "ms");
    }

    /**
     * Comprehensive segment validation
     * @param segmentFile The segment file to validate
     * @param expectedDurationMs Expected duration in milliseconds
     * @param qualityProfile Quality profile for additional validation
     * @return ValidationResult with details
     */
    public static ValidationResult validateSegment(File segmentFile, long expectedDurationMs, QualityProfile qualityProfile) {
        // Basic validation first
        ValidationResult durationResult = validateRecordingDuration(segmentFile, expectedDurationMs);
        if (!durationResult.isValid() && !durationResult.isWarning()) {
            return durationResult;
        }

        // File size validation
        if (qualityProfile != null) {
            long expectedSize = qualityProfile.getEstimatedSegmentSize(expectedDurationMs);
            long actualSize = segmentFile.length();

            // Allow 50% variance in file size (audio compression can vary significantly)
            long sizeDiff = Math.abs(actualSize - expectedSize);
            double sizeVariance = (double) sizeDiff / expectedSize;

            if (sizeVariance > 0.8) { // More than 80% variance
                String message = String.format("File size variance: expected≈%dKB, actual=%dKB, variance=%.1f%%",
                    expectedSize / 1024, actualSize / 1024, sizeVariance * 100);
                Log.w(TAG, message);

                if (sizeVariance > 2.0) { // More than 200% variance
                    return ValidationResult.warning(message + " (significant variance)");
                }
            }
        }

        // Audio format validation
        ValidationResult formatResult = validateAudioFormat(segmentFile);
        if (!formatResult.isValid()) {
            return formatResult;
        }

        // If duration had a warning, return that, otherwise return success
        return durationResult.isWarning() ? durationResult : ValidationResult.valid("Segment validation passed");
    }

    /**
     * Validate audio format and container
     * @param segmentFile The segment file to validate
     * @return ValidationResult with details
     */
    public static ValidationResult validateAudioFormat(File segmentFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(segmentFile.getAbsolutePath());

            // Check if it has audio track
            String hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            if (!"yes".equals(hasAudio)) {
                return ValidationResult.invalid("File does not contain audio track");
            }

            // Check MIME type
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            if (mimeType == null || !mimeType.startsWith("audio/")) {
                return ValidationResult.warning("Unexpected MIME type: " + mimeType);
            }

            return ValidationResult.valid("Audio format validation passed");

        } catch (Exception e) {
            Log.w(TAG, "Failed to validate audio format for: " + segmentFile.getName(), e);
            return ValidationResult.warning("Could not validate audio format: " + e.getMessage());
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
    }

    /**
     * Check if storage space is sufficient for recording
     * @param directory Target directory for recording
     * @param requiredSpaceBytes Required space in bytes
     * @return ValidationResult with details
     */
    public static ValidationResult validateStorageSpace(File directory, long requiredSpaceBytes) {
        if (directory == null || !directory.exists()) {
            return ValidationResult.invalid("Directory does not exist");
        }

        try {
            long usableSpace = directory.getUsableSpace();

            if (usableSpace < requiredSpaceBytes) {
                String message = String.format("Insufficient storage: need %dMB, available %dMB",
                    requiredSpaceBytes / (1024 * 1024), usableSpace / (1024 * 1024));
                return ValidationResult.invalid(message);
            }

            // Warn if less than 2x required space (safety margin)
            if (usableSpace < requiredSpaceBytes * 2) {
                String message = String.format("Low storage warning: need %dMB, available %dMB",
                    requiredSpaceBytes / (1024 * 1024), usableSpace / (1024 * 1024));
                return ValidationResult.warning(message);
            }

            return ValidationResult.valid("Storage space validation passed");

        } catch (Exception e) {
            Log.w(TAG, "Failed to check storage space", e);
            return ValidationResult.warning("Could not validate storage space: " + e.getMessage());
        }
    }

    /**
     * Get actual audio duration from file using MediaMetadataRetriever
     */
    private static long getActualAudioDuration(File audioFile) {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            return 0;
        }

        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(audioFile.getAbsolutePath());
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

            if (durationStr == null || durationStr.isEmpty()) {
                Log.w(TAG, "No duration metadata found for: " + audioFile.getName());

                // Fallback: estimate duration based on file size (rough approximation)
                // AAC at ~128kbps = ~16KB/s, so estimate duration from file size
                long estimatedDurationMs = (audioFile.length() * 1000L) / 16000L; // 16KB/s = 16 bytes/ms
                Log.d(TAG, "Using estimated duration: " + estimatedDurationMs + "ms for " + audioFile.getName());
                return Math.max(0, estimatedDurationMs);
            }

            return Long.parseLong(durationStr);

        } catch (Exception e) {
            Log.w(TAG, "Failed to get audio duration for: " + audioFile.getName(), e);
            return 0;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
    }

    /**
     * Result of a validation operation
     */
    public static class ValidationResult {
        private final boolean valid;
        private final boolean warning;
        private final String message;

        private ValidationResult(boolean valid, boolean warning, String message) {
            this.valid = valid;
            this.warning = warning;
            this.message = message;
        }

        public static ValidationResult valid(String message) {
            return new ValidationResult(true, false, message);
        }

        public static ValidationResult warning(String message) {
            return new ValidationResult(true, true, message);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, false, message);
        }

        public boolean isValid() {
            return valid;
        }

        public boolean isWarning() {
            return warning;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public String toString() {
            String type = valid ? (warning ? "WARNING" : "VALID") : "INVALID";
            return type + ": " + message;
        }
    }
}