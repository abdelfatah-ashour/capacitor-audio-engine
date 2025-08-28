package com.capacitor.audioengine;

/**
 * Configuration constants for the Audio Engine
 */
public class AudioEngineConfig {

    public static final class Recording {
        // Defaults aligned with Rolling Audio Recording Checklist: 48kHz mono AAC at 128 kbps
        public static final int DEFAULT_SAMPLE_RATE = 48000;
        public static final int DEFAULT_CHANNELS = 1;
        public static final int DEFAULT_BITRATE = 128000;
        public static final long MAX_RECORDING_DURATION_MS = 24 * 60 * 60 * 1000; // 24 hours
    }
}
