package com.capacitor.audioengine;

import java.io.File;
import java.util.Objects;

/**
 * Configuration for segment rolling recording
 * Provides configurable parameters for segment-based audio recording
 */
public class SegmentRollingConfig {
    private final long maxDurationMs;
    private final long segmentLengthMs;
    private final int maxSegments;
    private final int bufferPaddingSegments;
    private final boolean preMergeEnabled;
    private final QualityProfile qualityProfile;
    private final AudioRecordingConfig audioConfig;
    private final File outputDirectory;

    private SegmentRollingConfig(Builder builder) {
        // Validate inputs before calculation
        if (builder.maxDurationMs <= 0) {
            throw new IllegalArgumentException("maxDurationMs must be positive, got: " + builder.maxDurationMs);
        }
        if (builder.segmentLengthMs <= 0) {
            throw new IllegalArgumentException("segmentLengthMs must be positive, got: " + builder.segmentLengthMs);
        }
        if (builder.bufferPaddingSegments < 0) {
            throw new IllegalArgumentException("bufferPaddingSegments must be non-negative, got: " + builder.bufferPaddingSegments);
        }

        this.maxDurationMs = builder.maxDurationMs;
        this.segmentLengthMs = builder.segmentLengthMs;
        this.bufferPaddingSegments = builder.bufferPaddingSegments;
        this.preMergeEnabled = builder.preMergeEnabled;
        this.qualityProfile = builder.qualityProfile;

        // Calculate maxSegments dynamically based on maxDuration and segmentLength
        int calculatedMaxSegments = (int) Math.ceil((double) builder.maxDurationMs / builder.segmentLengthMs);
        // Add buffer padding for safety
        this.maxSegments = Math.max(1, calculatedMaxSegments + bufferPaddingSegments);

        // Set audio config from quality profile if not explicitly provided
        // Fallback to balanced quality
        if (builder.audioConfig != null) {
            this.audioConfig = builder.audioConfig;
        } else
            this.audioConfig = Objects.requireNonNullElse(builder.qualityProfile, QualityProfile.HIGH).createAudioConfig();

        this.outputDirectory = builder.outputDirectory;

        // Debug logging
        android.util.Log.d("SegmentRollingConfig", "Config created:");
        android.util.Log.d("SegmentRollingConfig", "  - maxDurationMs: " + maxDurationMs);
        android.util.Log.d("SegmentRollingConfig", "  - segmentLengthMs: " + segmentLengthMs);
        android.util.Log.d("SegmentRollingConfig", "  - calculatedMaxSegments: " + calculatedMaxSegments);
        android.util.Log.d("SegmentRollingConfig", "  - bufferPaddingSegments: " + bufferPaddingSegments);
        android.util.Log.d("SegmentRollingConfig", "  - final maxSegments: " + maxSegments);
        android.util.Log.d("SegmentRollingConfig", "  - preMergeEnabled: " + preMergeEnabled);
        android.util.Log.d("SegmentRollingConfig", "  - qualityProfile: " + (qualityProfile != null ? qualityProfile.name() : "null"));
    }

    public long getMaxDurationMs() {
        return maxDurationMs;
    }

    public long getSegmentLengthMs() {
        return segmentLengthMs;
    }

    public int getMaxSegments() {
        return maxSegments;
    }

    public int getBufferPaddingSegments() {
        return bufferPaddingSegments;
    }

    public boolean isPreMergeEnabled() {
        return preMergeEnabled;
    }

    public QualityProfile getQualityProfile() {
        return qualityProfile;
    }

    public AudioRecordingConfig getAudioConfig() {
        return audioConfig;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Builder for SegmentRollingConfig
     */
    public static class Builder {
        private long maxDurationMs = 300000; // 5 minutes default (mobile-optimized)
        private long segmentLengthMs = 25000; // 25 seconds default (optimal balance for rolling)
        private int bufferPaddingSegments = 1; // Keep 1 extra segment for safety
        private final boolean preMergeEnabled = true; // Enable pre-merge by default for better performance
        private QualityProfile qualityProfile = QualityProfile.HIGH; // Default to balanced quality
        private AudioRecordingConfig audioConfig;
        private File outputDirectory;

        /**
         * Set maximum recording duration
         * @param maxDurationMs Duration in milliseconds
         * @return Builder instance
         */
        public Builder setMaxDurationMs(long maxDurationMs) {
            this.maxDurationMs = maxDurationMs;
            // maxSegments will be calculated in constructor
            return this;
        }

        /**
         * Set maximum recording duration in seconds
         * @param maxDurationSeconds Duration in seconds
         * @return Builder instance
         */
        public Builder setMaxDurationSeconds(long maxDurationSeconds) {
            return setMaxDurationMs(maxDurationSeconds * 1000);
        }

        /**
         * Set segment length
         * @param segmentLengthMs Length in milliseconds
         * @return Builder instance
         */
        public Builder setSegmentLengthMs(long segmentLengthMs) {
            this.segmentLengthMs = segmentLengthMs;
            // maxSegments will be calculated in constructor
            return this;
        }

        /**
         * Set buffer padding segments for safety margin
         * @param bufferPaddingSegments Number of extra segments to keep in buffer
         * @return Builder instance
         */
        public Builder setBufferPaddingSegments(int bufferPaddingSegments) {
            this.bufferPaddingSegments = bufferPaddingSegments;
            return this;
        }

        /**
         * Set quality profile for audio recording
         * @param qualityProfile Quality profile to use
         * @return Builder instance
         */
        public Builder setQualityProfile(QualityProfile qualityProfile) {
            this.qualityProfile = qualityProfile;
            return this;
        }

        /**
         * Set output directory for segments
         * @param outputDirectory Directory for segment files
         * @return Builder instance
         */
        public Builder setOutputDirectory(File outputDirectory) {
            this.outputDirectory = outputDirectory;
            return this;
        }

        /**
         * Build the configuration
         * @return SegmentRollingConfig instance
         */
        public SegmentRollingConfig build() {
            if (outputDirectory == null) {
                throw new IllegalStateException("OutputDirectory is required");
            }
            if (maxDurationMs <= 0) {
                throw new IllegalStateException("MaxDuration must be positive");
            }
            if (segmentLengthMs <= 0) {
                throw new IllegalStateException("SegmentLength must be positive");
            }
            if (bufferPaddingSegments < 0) {
                throw new IllegalStateException("BufferPaddingSegments must be non-negative");
            }

            return new SegmentRollingConfig(this);
        }
    }

    @Override
    public String toString() {
        return "SegmentRollingConfig{" +
                "maxDurationMs=" + maxDurationMs +
                ", segmentLengthMs=" + segmentLengthMs +
                ", maxSegments=" + maxSegments +
                ", bufferPaddingSegments=" + bufferPaddingSegments +
                ", qualityProfile=" + (qualityProfile != null ? qualityProfile.name() : "null") +
                ", outputDirectory=" + (outputDirectory != null ? outputDirectory.getAbsolutePath() : "null") +
                '}';
    }
}
