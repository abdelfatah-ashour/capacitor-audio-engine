package com.capacitor.audioengine;

/**
 * Supported audio formats for recording
 */
public enum AudioRecordingFormat {
    /**
     * MPEG-4 AAC format (default)
     * - Uses MediaRecorder
     * - Compressed format
     * - Good quality-to-size ratio
     * - Slower stop times due to encoding
     */
    M4A("m4a", "audio/m4a", true),

    /**
     * WAV PCM format
     * - Uses AudioRecord with direct PCM recording
     * - Uncompressed format
     * - Larger file sizes
     * - Faster stop times (no encoding)
     * - More precise timing control
     */
    WAV("wav", "audio/wav", false);

    private final String extension;
    private final String mimeType;
    private final boolean isCompressed;

    AudioRecordingFormat(String extension, String mimeType, boolean isCompressed) {
        this.extension = extension;
        this.mimeType = mimeType;
        this.isCompressed = isCompressed;
    }

    public String getExtension() {
        return extension;
    }

    public String getMimeType() {
        return mimeType;
    }

    public boolean isCompressed() {
        return isCompressed;
    }

    /**
     * Get audio format from string
     */
    public static AudioRecordingFormat fromString(String format) {
        if (format == null) {
            return M4A; // Default
        }

        String normalized = format.toLowerCase().trim();
        switch (normalized) {
            case "wav":
            case "pcm":
                return WAV;
            case "m4a":
            case "aac":
            case "mp4":
            default:
                return M4A;
        }
    }

    /**
     * Check if format is suitable for fast stop requirements
     */
    public boolean isFastStop() {
        return !isCompressed; // Uncompressed formats stop faster
    }

    /**
     * Get recommended format for given requirements
     */
    public static AudioRecordingFormat getRecommendedFormat(boolean needsFastStop, boolean needsSmallSize) {
        if (needsFastStop && !needsSmallSize) {
            return WAV; // Fast stop, larger files OK
        } else {
            return M4A; // Default for most use cases
        }
    }
}
