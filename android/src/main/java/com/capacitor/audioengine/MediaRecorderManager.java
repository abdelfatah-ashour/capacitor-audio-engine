package com.capacitor.audioengine;

import android.media.MediaRecorder;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/**
 * Manages MediaRecorder setup and configuration
 */
public class MediaRecorderManager {
    private static final String TAG = "MediaRecorderManager";

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;

    /**
     * Setup MediaRecorder with specified configuration
     */
    public void setupMediaRecorder(String outputPath, AudioRecordingConfig config) throws IOException {
        if (outputPath == null || outputPath.trim().isEmpty()) {
            throw new IOException("Output path cannot be null or empty");
        }

        if (config == null) {
            throw new IOException("Audio recording config cannot be null");
        }

        // Ensure parent directory exists
        File outputFile = new File(outputPath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new IOException("Failed to create parent directory: " + parentDir.getAbsolutePath());
            }
        }

        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                }
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing existing MediaRecorder", e);
            } finally {
                isRecording = false;
            }
        }

        try {
            mediaRecorder = new MediaRecorder();

            // Use MIC audio source - most reliable for long recording sessions
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

            // Use MPEG_4 container format for M4A output
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

            // Use AAC encoder for best compatibility and compression
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

            // Set output file path
            mediaRecorder.setOutputFile(outputPath);

            // Configure audio settings for high quality and efficiency
            mediaRecorder.setAudioSamplingRate(config.getSampleRate());
            mediaRecorder.setAudioChannels(config.getChannels());
            mediaRecorder.setAudioEncodingBitRate(config.getBitrate());



            // Prepare the recorder
            mediaRecorder.prepare();

            Log.d(TAG, "MediaRecorder configured - Sample Rate: " + config.getSampleRate() +
                   ", Channels: " + config.getChannels() + ", Bitrate: " + config.getBitrate() +
                   ", Output: " + outputPath);

        } catch (Exception e) {
            Log.e(TAG, "Failed to setup MediaRecorder", e);

            // Clean up on failure
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.release();
                } catch (Exception releaseError) {
                    Log.w(TAG, "Error releasing MediaRecorder after setup failure", releaseError);
                }
                mediaRecorder = null;
            }
            isRecording = false;

            throw new IOException("Failed to setup MediaRecorder: " + e.getMessage(), e);
        }
    }

    /**
     * Start recording
     */
    public void startRecording() throws IllegalStateException {
        if (mediaRecorder == null) {
            throw new IllegalStateException("MediaRecorder not setup");
        }

        if (isRecording) {
            Log.w(TAG, "Start recording called but already recording");
            return;
        }

        try {
            mediaRecorder.start();
            isRecording = true;
            Log.d(TAG, "Recording started");
        } catch (Exception e) {
            isRecording = false;
            Log.e(TAG, "Failed to start recording", e);
            throw new IllegalStateException("Failed to start recording: " + e.getMessage(), e);
        }
    }

    /**
     * Stop recording
     */
    public void stopRecording() throws RuntimeException {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                Log.d(TAG, "Recording stopped");
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaRecorder", e);
                // Still mark as not recording to prevent state inconsistency
                throw new RuntimeException("Failed to stop recording: " + e.getMessage(), e);
            } finally {
                isRecording = false;
            }
        } else {
            Log.w(TAG, "Stop recording called but recorder is not in recording state");
        }
    }

    /**
     * Pause recording (Android N+)
     */
    public void pauseRecording() throws RuntimeException {
        if (mediaRecorder != null && isRecording) {
            mediaRecorder.pause();
            Log.d(TAG, "Recording paused");
        } else {
            throw new RuntimeException("Pause not supported on this Android version or no active recording");
        }
    }

    /**
     * Resume recording (Android N+)
     */
    public void resumeRecording() throws RuntimeException {
        if (mediaRecorder != null) {
            mediaRecorder.resume();
            Log.d(TAG, "Recording resumed");
        } else {
            throw new RuntimeException("Resume not supported on this Android version");
        }
    }

    /**
     * Release MediaRecorder resources
     */
    public void release() {
        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                }
                mediaRecorder.release();
                Log.d(TAG, "MediaRecorder released");
            } catch (Exception e) {
                Log.w(TAG, "Error releasing MediaRecorder", e);
            } finally {
                mediaRecorder = null;
                isRecording = false;
            }
        }
    }

    /**
     * Check if currently recording
     */
    public boolean isRecording() {
        return isRecording && mediaRecorder != null;
    }


    /**
     * Check if pause/resume is supported on this device
     */
    public boolean isPauseResumeSupported() {
        return true;
    }
}
