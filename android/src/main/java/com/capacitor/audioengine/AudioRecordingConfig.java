package com.capacitor.audioengine;

/**
 * Configuration class for audio recording parameters with validation
 */
public class AudioRecordingConfig {
    private final int sampleRate;
    private final int channels;
    private final int bitrate;
    private final Integer maxDuration;
    private final boolean enableBackgroundRecording;
    private final String outputFormat;

    private AudioRecordingConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.channels = builder.channels;
        this.bitrate = builder.bitrate;
        this.maxDuration = builder.maxDuration;
        this.enableBackgroundRecording = builder.enableBackgroundRecording;
        this.outputFormat = builder.outputFormat;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannels() {
        return channels;
    }

    public int getBitrate() {
        return bitrate;
    }

    public Integer getMaxDuration() {
        return maxDuration;
    }

    public boolean isBackgroundRecordingEnabled() {
        return enableBackgroundRecording;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    /**
     * Validate configuration parameters
     */
    public void validate() throws IllegalArgumentException {
        ValidationUtils.validateAudioParameters(sampleRate, channels, bitrate);
        ValidationUtils.validateMaxDuration(maxDuration);

        if (outputFormat == null || outputFormat.trim().isEmpty()) {
            throw new IllegalArgumentException("Output format cannot be null or empty");
        }
    }

    /**
     * Builder pattern for creating AudioRecordingConfig instances
     */
    public static class Builder {
        private int sampleRate = 44100;
        private int channels = 1;
        private int bitrate = 128000;
        private Integer maxDuration = null;
        private boolean enableBackgroundRecording = true;
        private String outputFormat = "m4a";

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder channels(int channels) {
            this.channels = channels;
            return this;
        }

        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            return this;
        }

        public Builder maxDuration(Integer maxDuration) {
            this.maxDuration = maxDuration;
            return this;
        }

        public Builder enableBackgroundRecording(boolean enableBackgroundRecording) {
            this.enableBackgroundRecording = enableBackgroundRecording;
            return this;
        }

        public Builder outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
            return this;
        }

        /**
         * Create configuration from Capacitor plugin call parameters
         */
        public Builder fromPluginCall(com.getcapacitor.PluginCall call) {
            if (call.getInt("sampleRate") != null) {
                sampleRate(call.getInt("sampleRate"));
            }
            if (call.getInt("channels") != null) {
                channels(call.getInt("channels"));
            }
            if (call.getInt("bitrate") != null) {
                bitrate(call.getInt("bitrate"));
            }
            if (call.getInt("maxDuration") != null) {
                maxDuration(call.getInt("maxDuration"));
            }
            if (call.getBoolean("enableBackgroundRecording") != null) {
                enableBackgroundRecording(call.getBoolean("enableBackgroundRecording"));
            }
            if (call.getString("outputFormat") != null) {
                outputFormat(call.getString("outputFormat"));
            }
            return this;
        }

        public AudioRecordingConfig build() {
            AudioRecordingConfig config = new AudioRecordingConfig(this);
            config.validate(); // Validate on build
            return config;
        }
    }

    @Override
    public String toString() {
        return String.format("AudioRecordingConfig{sampleRate=%d, channels=%d, bitrate=%d, maxDuration=%s, backgroundRecording=%s, outputFormat='%s'}",
                sampleRate, channels, bitrate, maxDuration, enableBackgroundRecording, outputFormat);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioRecordingConfig that = (AudioRecordingConfig) o;

        if (sampleRate != that.sampleRate) return false;
        if (channels != that.channels) return false;
        if (bitrate != that.bitrate) return false;
        if (enableBackgroundRecording != that.enableBackgroundRecording) return false;
        if (maxDuration != null ? !maxDuration.equals(that.maxDuration) : that.maxDuration != null) return false;
        return outputFormat != null ? outputFormat.equals(that.outputFormat) : that.outputFormat == null;
    }

    @Override
    public int hashCode() {
        int result = sampleRate;
        result = 31 * result + channels;
        result = 31 * result + bitrate;
        result = 31 * result + (maxDuration != null ? maxDuration.hashCode() : 0);
        result = 31 * result + (enableBackgroundRecording ? 1 : 0);
        result = 31 * result + (outputFormat != null ? outputFormat.hashCode() : 0);
        return result;
    }
}
