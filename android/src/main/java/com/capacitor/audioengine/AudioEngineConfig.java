package com.capacitor.audioengine;

/**
 * Configuration constants for the Audio Engine
 */
public class AudioEngineConfig {

    public static final class Recording {
        // Defaults for high-quality audio recording: 48kHz mono AAC at 128 kbps
        public static final int DEFAULT_SAMPLE_RATE = 48000;
        public static final int DEFAULT_CHANNELS = 1;
        public static final int DEFAULT_BITRATE = 128000;
    }
}
