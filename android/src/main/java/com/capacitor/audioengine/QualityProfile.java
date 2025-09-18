package com.capacitor.audioengine;

import androidx.annotation.NonNull;

/**
 * Quality profiles for audio recording with predefined settings
 * optimized for different use cases and device capabilities.
 */
public enum QualityProfile {
    /**
     * Low quality - optimized for minimal storage and bandwidth
     * Sample rate: 16kHz, Bitrate: 64kbps
     */
    LOW(16000, 64000, "Low quality - minimal storage"),

    /**
     * Balanced quality - good compromise between quality and file size
     * Sample rate: 44.1kHz, Bitrate: 128kbps
     */
    BALANCED(44100, 128000, "Balanced quality - recommended default"),

    /**
     * High quality - optimized for audio quality
     * Sample rate: 48kHz, Bitrate: 256kbps
     */
    HIGH(48000, 256000, "High quality - larger file size"),

    /**
     * Voice optimized - optimized for speech recording
     * Sample rate: 16kHz, Bitrate: 96kbps
     */
    VOICE(16000, 96000, "Voice optimized - speech recording");

    private final int sampleRate;
    private final int bitrate;
    private final String description;

    QualityProfile(int sampleRate, int bitrate, String description) {
        this.sampleRate = sampleRate;
        this.bitrate = bitrate;
        this.description = description;
    }

    /**
     * Estimate file size per second for this quality profile
     * @return Estimated bytes per second of audio
     */
    public long getEstimatedBytesPerSecond() {
        // Convert bitrate to bytes per second (divide by 8)
        // Add some overhead for container format (~10%)
        return (long) (bitrate / 8.0 * 1.1);
    }

    /**
     * Estimate segment file size for given duration
     * @param durationMs Duration in milliseconds
     * @return Estimated file size in bytes
     */
    public long getEstimatedSegmentSize(long durationMs) {
        return (long) (getEstimatedBytesPerSecond() * (durationMs / 1000.0));
    }

    /**
     * Create AudioRecordingConfig from this quality profile
     * @return AudioRecordingConfig with this profile's settings
     */
    public AudioRecordingConfig createAudioConfig() {
        return new AudioRecordingConfig.Builder()
            .sampleRate(sampleRate)
            .bitrate(bitrate)
            .build();
    }

    @NonNull
    @Override
    public String toString() {
        return "{" +
               "sampleRate=" + sampleRate + "Hz" +
               ", bitrate=" + bitrate + "bps" +
               ", description='" + description + '\'' +
               '}';
    }
}