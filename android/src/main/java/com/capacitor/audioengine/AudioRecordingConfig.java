package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import androidx.annotation.RequiresPermission;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

/**
 * Configuration class for audio recording parameters with validation
 * Also provides microphone-related utility methods
 */
public class AudioRecordingConfig {
    private final int sampleRate;
    private final int channels;
    private final int bitrate;

    private final String outputFormat;

    private AudioRecordingConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.channels = builder.channels;
        this.bitrate = builder.bitrate;

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





    public String getOutputFormat() {
        return outputFormat;
    }

    /**
     * Validate configuration parameters
     */
    public void validate() throws IllegalArgumentException {
        ValidationUtils.validateAudioParameters(sampleRate, channels, bitrate);

        if (outputFormat == null || outputFormat.trim().isEmpty()) {
            throw new IllegalArgumentException("Output format cannot be null or empty");
        }
    }

    /**
     * Builder pattern for creating AudioRecordingConfig instances
     */
    public static class Builder {
        private int sampleRate = 22050;  // Reduced from 44100 for smaller files
        private int channels = 1;
        private int bitrate = 64000;      // Reduced from 128000 for smaller files


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





        public void outputFormat(String outputFormat) {
            this.outputFormat = outputFormat;
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
        return String.format("AudioRecordingConfig{sampleRate=%d, channels=%d, bitrate=%d, outputFormat='%s'}",
                sampleRate, channels, bitrate, outputFormat);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AudioRecordingConfig that = (AudioRecordingConfig) o;

        if (sampleRate != that.sampleRate) return false;
        if (channels != that.channels) return false;
        if (bitrate != that.bitrate) return false;


        return outputFormat != null ? outputFormat.equals(that.outputFormat) : that.outputFormat == null;
    }

    @Override
    public int hashCode() {
        int result = sampleRate;
        result = 31 * result + channels;
        result = 31 * result + bitrate;


        result = 31 * result + (outputFormat != null ? outputFormat.hashCode() : 0);
        return result;
    }

    // Microphone utility methods

    private static final String TAG = "AudioRecordingConfig";

    /**
     * Get array of available microphones on the device
     */
    public static JSArray getAvailableMicrophonesArray(Context context) {
        JSArray microphones = new JSArray();

        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return microphones;
            }

            // Get audio input devices (API 23+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);

                for (AudioDeviceInfo device : devices) {
                    if (isInputDevice(device)) {
                        JSObject micInfo = new JSObject();
                        micInfo.put("id", device.getId());
                        micInfo.put("name", getDeviceName(device));
                        micInfo.put("type", getDeviceType(device));
                        micInfo.put("isConnected", true);
                        microphones.put(micInfo);
                    }
                }
            } else {
                // Fallback for older Android versions - add built-in microphone
                JSObject builtInMic = new JSObject();
                builtInMic.put("id", 0);
                builtInMic.put("name", "Built-in Microphone");
                builtInMic.put("type", "BUILTIN");
                builtInMic.put("isConnected", true);
                microphones.put(builtInMic);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error getting available microphones", e);
        }

        return microphones;
    }

    /**
     * Check if device is an input device (microphone)
     */
    public static boolean isInputDevice(AudioDeviceInfo device) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            int type = device.getType();
            return type == AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                   type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                   type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                   type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                   type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                   type == AudioDeviceInfo.TYPE_USB_DEVICE;
        }
        return false;
    }

    /**
     * Get human-readable device name
     */
    public static String getDeviceName(AudioDeviceInfo device) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                CharSequence productName = device.getProductName();
                if (productName != null && productName.length() > 0) {
                    return productName.toString();
                }
            }

            // Fallback to type-based naming
            return getDeviceTypeDisplayName(device.getType());
        }
        return "Unknown";
    }

    /**
     * Get device type string
     */
    public static String getDeviceType(AudioDeviceInfo device) {
      int type = device.getType();
      return switch (type) {
        case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN";
        case AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET";
        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED_HEADPHONES";
        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH";
        case AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET";
        case AudioDeviceInfo.TYPE_USB_DEVICE -> "USB";
        default -> "OTHER";
      };
    }

    /**
     * Get display name for device type
     */
    public static String getDeviceTypeDisplayName(int type) {
      return switch (type) {
        case AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone";
        case AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset";
        case AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones";
        case AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset";
        case AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset";
        case AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device";
        default -> "Other Audio Device";
      };
    }

    /**
     * Check if microphone is currently being used by another application
     * by attempting to create and start an AudioRecord instance
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public static boolean checkMicrophoneAvailability() {
        AudioRecord audioRecord = null;
        try {
            Log.d(TAG, "Starting microphone availability check...");

            // Use standard parameters for testing (matching optimized defaults)
            int sampleRate = 22050;
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            // Calculate minimum buffer size
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            Log.d(TAG, "AudioRecord buffer size: " + bufferSize);

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "Failed to get valid buffer size, microphone may be unavailable");
                return true;
            }

            // Attempt to create AudioRecord instance
            Log.d(TAG, "Creating AudioRecord instance...");
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );

            // Check if AudioRecord was created successfully
            int state = audioRecord.getState();
            Log.d(TAG, "AudioRecord state: " + state + " (expected: " + AudioRecord.STATE_INITIALIZED + ")");

            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize, microphone may be busy or unavailable");
                return true;
            }

            // Try to start recording briefly
            Log.d(TAG, "Starting recording test...");
            audioRecord.startRecording();

            // Give it a moment to start
            try {
                Thread.sleep(100); // Brief delay to allow recording to start
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Check if recording actually started
            int recordingState = audioRecord.getRecordingState();
            Log.d(TAG, "AudioRecord recording state: " + recordingState + " (expected: " + AudioRecord.RECORDSTATE_RECORDING + ")");

            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "AudioRecord failed to start recording, microphone may be busy");
                return true;
            }

            // Test if we can actually read some data
            byte[] buffer = new byte[bufferSize / 4]; // Small buffer for testing
            int bytesRead = audioRecord.read(buffer, 0, buffer.length);
            Log.d(TAG, "AudioRecord read test: " + bytesRead + " bytes");

            if (bytesRead < 0) {
                Log.w(TAG, "AudioRecord read failed, microphone may be busy");
                return true;
            }

            // If we reached here, microphone is available
            Log.d(TAG, "Microphone availability check passed - microphone is available");
            return false;

        } catch (SecurityException e) {
            // Permission denied - this should be caught earlier, but just in case
            Log.w(TAG, "Security exception while checking microphone availability", e);
            return true;
        } catch (IllegalArgumentException e) {
            // Invalid parameters - may indicate microphone issues
            Log.w(TAG, "Illegal argument exception while checking microphone availability", e);
            return true;
        } catch (Exception e) {
            // Any other exception could mean the microphone is busy or unavailable
            Log.w(TAG, "Unexpected exception while checking microphone availability", e);
            return true;
        } finally {
            if (audioRecord != null) {
                try {
                    if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop();
                    }
                    Log.d(TAG, "Releasing AudioRecord...");
                    audioRecord.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing AudioRecord", e);
                }
            }
        }
    }

    /**
     * Secondary check for microphone availability using different parameters
     * This is more lenient and focuses on basic initialization
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public static boolean checkMicrophoneAvailabilitySecondary() {
        AudioRecord audioRecord = null;
        try {
            Log.d(TAG, "Starting secondary microphone availability check...");

            // Use simpler parameters that are more likely to work
            int sampleRate = 8000; // Lower sample rate
            int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_16BIT;

            // Calculate minimum buffer size
            int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            Log.d(TAG, "Secondary check - buffer size: " + bufferSize);

            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.w(TAG, "Secondary check - failed to get valid buffer size");
                return true;
            }

            // Double the buffer size for more stability
            bufferSize = bufferSize * 2;

            // Attempt to create AudioRecord instance
            Log.d(TAG, "Secondary check - creating AudioRecord...");
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );

            // Check if AudioRecord was created successfully
            int state = audioRecord.getState();
            Log.d(TAG, "Secondary check - AudioRecord state: " + state);

            if (state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Secondary check - AudioRecord failed to initialize");
                return true;
            }

            // For secondary check, we only test initialization, not actual recording
            // This is less invasive and more reliable for determining availability
            Log.d(TAG, "Secondary check passed - microphone appears available");
            return false;

        } catch (Exception e) {
            Log.w(TAG, "Secondary check exception", e);
            return true;
        } finally {
            if (audioRecord != null) {
                try {
                    audioRecord.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing AudioRecord in secondary check", e);
                }
            }
        }
    }
}
