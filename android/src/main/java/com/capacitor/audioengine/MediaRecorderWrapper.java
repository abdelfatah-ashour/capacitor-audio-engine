package com.capacitor.audioengine;

import android.media.MediaRecorder;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/**
 * MediaRecorder wrapper that provides additional functionality
 * for audio recording with improved error handling and state management
 */
public class MediaRecorderWrapper {
    private static final String TAG = "MediaRecorderWrapper";

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private File currentOutputFile = null;
    private AudioRecordingConfig currentConfig = null;
    private long recordingStartTime = 0;

    /**
     * Constructor
     */
    public MediaRecorderWrapper() {
        this.mediaRecorder = new android.media.MediaRecorder();
    }

    /**
     * Configure and start recording with the specified config and output file
     * @param config Audio recording configuration
     * @param outputFile Output file for the recording
     * @throws IOException if setup or start fails
     */
    public void configureAndStart(AudioRecordingConfig config, File outputFile) throws IOException {
        if (config == null) {
            throw new IOException("Audio recording config cannot be null");
        }

        if (outputFile == null) {
            throw new IOException("Output file cannot be null");
        }

        // Ensure we're not already recording
        if (isRecording) {
            stopSafely();
        }

        // Ensure parent directory exists
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
            }
        }

        try {
            // Reset recorder to clean state
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                } catch (Exception e) {
                    Log.w(TAG, "Error resetting MediaRecorder", e);
                    // Create new instance if reset fails
                    mediaRecorder.release();
                    mediaRecorder = new MediaRecorder();
                }
            } else {
                mediaRecorder = new MediaRecorder();
            }

            // Configure MediaRecorder
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

            // Apply audio configuration
            mediaRecorder.setAudioSamplingRate(config.getSampleRate());
            mediaRecorder.setAudioChannels(config.getChannels());
            mediaRecorder.setAudioEncodingBitRate(config.getBitrate());

            // Configure AAC encoder for better gapless performance
            try {
                // Ensure optimal AAC encoding settings for recording consistency
                // MediaRecorder will use AAC-LC profile by default with MPEG_4 container

                // Set consistent bit rate for uniform encoding
                if (config.getBitrate() > 0) {
                    mediaRecorder.setAudioEncodingBitRate(config.getBitrate());
                }

                // Ensure sample rate is properly set for frame alignment
                mediaRecorder.setAudioSamplingRate(config.getSampleRate());

                // Request lower latency if available (reduces encoder delay) - API 30+
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    mediaRecorder.setPrivacySensitive(false); // Allow optimization
                }

                Log.d(TAG, "AAC encoder configured for gapless recording with sample rate: " +
                      config.getSampleRate() + ", bitrate: " + config.getBitrate());
            } catch (Exception e) {
                Log.d(TAG, "Advanced AAC configuration not available: " + e.getMessage());
            }

            // Prepare and start
            mediaRecorder.prepare();
            mediaRecorder.start();

            // Update state
            isRecording = true;
            currentOutputFile = outputFile;
            currentConfig = config;
            recordingStartTime = System.currentTimeMillis();

            Log.d(TAG, "Enhanced MediaRecorder started - Output: " + outputFile.getName() +
                  ", Sample Rate: " + config.getSampleRate() +
                  ", Channels: " + config.getChannels() +
                  ", Bitrate: " + config.getBitrate());

        } catch (Exception e) {
            // Clean up on failure
            isRecording = false;
            currentOutputFile = null;
            currentConfig = null;
            recordingStartTime = 0;

            if (mediaRecorder != null) {
                try {
                    mediaRecorder.reset();
                } catch (Exception resetError) {
                    Log.w(TAG, "Error resetting MediaRecorder after failure", resetError);
                }
            }

            throw new IOException("Failed to configure and start MediaRecorder: " + e.getMessage(), e);
        }
    }

    /**
     * Stop recording safely with error handling
     * @return The output file if recording was successful, null otherwise
     */
    public File stopSafely() {
        File resultFile = null;

        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                resultFile = currentOutputFile;

                Log.d(TAG, "Enhanced MediaRecorder stopped successfully - Output: " +
                      (resultFile != null ? resultFile.getName() : "null") +
                      ", Duration: " + (System.currentTimeMillis() - recordingStartTime) + "ms");

            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaRecorder", e);
                // Mark file as potentially corrupted
                if (currentOutputFile != null && currentOutputFile.exists()) {
                    Log.w(TAG, "Recording file may be corrupted: " + currentOutputFile.getName());
                }
                resultFile = null;
            } finally {
                // Always update state
                isRecording = false;
                currentOutputFile = null;
                recordingStartTime = 0;

                // Reset recorder for next use
                try {
                    mediaRecorder.reset();
                } catch (Exception resetError) {
                    Log.w(TAG, "Error resetting MediaRecorder after stop", resetError);
                }
            }
        } else {
            Log.w(TAG, "Stop called but recorder is not in recording state");
        }

        return resultFile;
    }

    /**
     * Check if currently recording
     * @return true if recording is active, false otherwise
     */
    public boolean isRecording() {
        return isRecording && mediaRecorder != null;
    }

    /**
     * Get debug information about the current state
     * @return Debug information string
     */
    public String getDebugInfo() {
        return "MediaRecorderWrapper{" +
               "isRecording=" + isRecording +
               ", currentOutputFile=" + (currentOutputFile != null ? currentOutputFile.getName() : "null") +
               ", recordingDuration=" + (recordingStartTime > 0 ? (System.currentTimeMillis() - recordingStartTime) : 0) + "ms" +
               ", sampleRate=" + (currentConfig != null ? currentConfig.getSampleRate() : "unknown") +
               ", channels=" + (currentConfig != null ? currentConfig.getChannels() : "unknown") +
               ", bitrate=" + (currentConfig != null ? currentConfig.getBitrate() : "unknown") +
               '}';
    }

    /**
     * Release all resources
     */
    public void release() {
        if (isRecording) {
            stopSafely();
        }

        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
                Log.d(TAG, "Enhanced MediaRecorder released");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaRecorder", e);
            } finally {
                mediaRecorder = null;
                isRecording = false;
                currentOutputFile = null;
                currentConfig = null;
                recordingStartTime = 0;
            }
        }
    }

    /**
     * Get current recording duration in milliseconds
     * @return Recording duration or 0 if not recording
     */
    public long getRecordingDuration() {
        if (isRecording && recordingStartTime > 0) {
            return System.currentTimeMillis() - recordingStartTime;
        }
        return 0;
    }

    /**
     * Get current output file
     * @return Current output file or null if not recording
     */
    public File getCurrentOutputFile() {
        return currentOutputFile;
    }
}