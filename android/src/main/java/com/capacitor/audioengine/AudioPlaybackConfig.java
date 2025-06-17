package com.capacitor.audioengine;

/**
 * Configuration class for audio playback parameters with validation
 */
public class AudioPlaybackConfig {
    private final float speed;
    private final float volume;
    private final boolean loop;
    private final int startTime;

    private AudioPlaybackConfig(Builder builder) {
        this.speed = builder.speed;
        this.volume = builder.volume;
        this.loop = builder.loop;
        this.startTime = builder.startTime;
    }

    public float getSpeed() {
        return speed;
    }

    public float getVolume() {
        return volume;
    }

    public boolean isLoop() {
        return loop;
    }

    public int getStartTime() {
        return startTime;
    }

    /**
     * Validate configuration parameters
     */
    public void validate() throws IllegalArgumentException {
        ValidationUtils.validatePlaybackParameters(speed, volume);

        if (startTime < 0) {
            throw new IllegalArgumentException("Start time cannot be negative");
        }
    }

    /**
     * Builder pattern for creating AudioPlaybackConfig instances
     */
    public static class Builder {
        private float speed = 1.0f;
        private float volume = 1.0f;
        private boolean loop = false;
        private int startTime = 0;

        public Builder speed(float speed) {
            this.speed = speed;
            return this;
        }

        public Builder volume(float volume) {
            this.volume = volume;
            return this;
        }

        public Builder loop(boolean loop) {
            this.loop = loop;
            return this;
        }

        public Builder startTime(int startTime) {
            this.startTime = startTime;
            return this;
        }

        /**
         * Create configuration from Capacitor plugin call parameters
         */
        public Builder fromPluginCall(com.getcapacitor.PluginCall call) {
            if (call.getFloat("speed") != null) {
                speed(call.getFloat("speed"));
            }
            if (call.getFloat("volume") != null) {
                volume(call.getFloat("volume"));
            }
            if (call.getBoolean("loop") != null) {
                loop(call.getBoolean("loop"));
            }
            if (call.getInt("startTime") != null) {
                startTime(call.getInt("startTime"));
            }
            return this;
        }

        public AudioPlaybackConfig build() {
            AudioPlaybackConfig config = new AudioPlaybackConfig(this);
            config.validate(); // Validate on build
            return config;
        }
    }

    @Override
    public String toString() {
        return String.format("AudioPlaybackConfig{speed=%.2f, volume=%.2f, loop=%s, startTime=%d}",
                speed, volume, loop, startTime);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioPlaybackConfig that = (AudioPlaybackConfig) o;

        if (Float.compare(that.speed, speed) != 0) return false;
        if (Float.compare(that.volume, volume) != 0) return false;
        if (loop != that.loop) return false;
        return startTime == that.startTime;
    }

    @Override
    public int hashCode() {
        int result = (speed != +0.0f ? Float.floatToIntBits(speed) : 0);
        result = 31 * result + (volume != +0.0f ? Float.floatToIntBits(volume) : 0);
        result = 31 * result + (loop ? 1 : 0);
        result = 31 * result + startTime;
        return result;
    }
}
