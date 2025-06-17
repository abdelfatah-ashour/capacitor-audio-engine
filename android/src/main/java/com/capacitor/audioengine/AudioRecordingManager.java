package com.capacitor.audioengine;

import android.content.Context;
import android.media.MediaRecorder;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages audio recording operations with proper state management and resource cleanup
 */
public class AudioRecordingManager {
    private static final String TAG = "AudioRecordingManager";

    private final Context context;
    private final AudioRecordingConfig config;
    private final RecordingStateListener stateListener;

    private MediaRecorder mediaRecorder;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicLong recordingStartTime = new AtomicLong(0);
    private final AtomicInteger currentSegment = new AtomicInteger(0);

    private String currentRecordingPath;
    private File recordingDirectory;

    public interface RecordingStateListener {
        void onRecordingStarted(String path);
        void onRecordingPaused();
        void onRecordingResumed();
        void onRecordingStopped(String finalPath);
        void onRecordingError(AudioEngineError error, String details);
        void onDurationUpdate(double durationSeconds);
    }

    public AudioRecordingManager(Context context, AudioRecordingConfig config, RecordingStateListener listener) {
        this.context = context;
        this.config = config;
        this.stateListener = listener;
    }

    /**
     * Start recording with the configured parameters
     */
    public synchronized void startRecording() throws AudioRecordingException {
        if (isRecording.get()) {
            throw new AudioRecordingException(AudioEngineError.RECORDING_IN_PROGRESS);
        }

        try {
            // Validate configuration
            config.validate();

            // Create recording directory
            createRecordingDirectory();

            // Setup MediaRecorder
            setupMediaRecorder();

            // Start recording
            mediaRecorder.start();

            // Update state
            isRecording.set(true);
            isPaused.set(false);
            recordingStartTime.set(System.currentTimeMillis());
            currentSegment.set(1);

            Log.d(TAG, "Recording started: " + currentRecordingPath);
            stateListener.onRecordingStarted(currentRecordingPath);

        } catch (Exception e) {
            cleanup();
            throw new AudioRecordingException(AudioEngineError.INITIALIZATION_FAILED, e.getMessage(), e);
        }
    }

    /**
     * Pause recording (API 24+)
     */
    public synchronized void pauseRecording() throws AudioRecordingException {
        if (!isRecording.get()) {
            throw new AudioRecordingException(AudioEngineError.NO_ACTIVE_RECORDING);
        }

        if (isPaused.get()) {
            throw new AudioRecordingException(AudioEngineError.INVALID_STATE, "Recording is already paused");
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                mediaRecorder.pause();
                isPaused.set(true);
                Log.d(TAG, "Recording paused");
                stateListener.onRecordingPaused();
            } else {
                throw new AudioRecordingException(AudioEngineError.INVALID_STATE,
                    "Pause/Resume not supported on Android versions below API 24");
            }
        } catch (Exception e) {
            throw new AudioRecordingException(AudioEngineError.INVALID_STATE, e.getMessage(), e);
        }
    }

    /**
     * Resume recording (API 24+)
     */
    public synchronized void resumeRecording() throws AudioRecordingException {
        if (!isRecording.get()) {
            throw new AudioRecordingException(AudioEngineError.NO_ACTIVE_RECORDING);
        }

        if (!isPaused.get()) {
            throw new AudioRecordingException(AudioEngineError.INVALID_STATE, "Recording is not paused");
        }

        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                mediaRecorder.resume();
                isPaused.set(false);
                Log.d(TAG, "Recording resumed");
                stateListener.onRecordingResumed();
            } else {
                throw new AudioRecordingException(AudioEngineError.INVALID_STATE,
                    "Pause/Resume not supported on Android versions below API 24");
            }
        } catch (Exception e) {
            throw new AudioRecordingException(AudioEngineError.INVALID_STATE, e.getMessage(), e);
        }
    }

    /**
     * Stop recording and return the final file path
     */
    public synchronized String stopRecording() throws AudioRecordingException {
        if (!isRecording.get()) {
            throw new AudioRecordingException(AudioEngineError.NO_ACTIVE_RECORDING);
        }

        try {
            String finalPath = currentRecordingPath;

            // Stop MediaRecorder
            mediaRecorder.stop();
            cleanup();

            Log.d(TAG, "Recording stopped: " + finalPath);
            stateListener.onRecordingStopped(finalPath);

            return finalPath;

        } catch (Exception e) {
            cleanup();
            throw new AudioRecordingException(AudioEngineError.UNKNOWN_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Get current recording duration in seconds
     */
    public double getCurrentDuration() {
        if (!isRecording.get()) {
            return 0.0;
        }

        long elapsed = System.currentTimeMillis() - recordingStartTime.get();
        return elapsed / 1000.0;
    }

    /**
     * Check if currently recording
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    /**
     * Check if recording is paused
     */
    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * Get current segment number
     */
    public int getCurrentSegment() {
        return currentSegment.get();
    }

    /**
     * Get current recording path
     */
    public String getCurrentRecordingPath() {
        return currentRecordingPath;
    }

    /**
     * Clean up resources
     */
    public synchronized void cleanup() {
        if (mediaRecorder != null) {
            ResourceManager.releaseMediaRecorder(mediaRecorder);
            mediaRecorder = null;
        }

        isRecording.set(false);
        isPaused.set(false);
        currentRecordingPath = null;
        recordingStartTime.set(0);
        currentSegment.set(0);
    }

    private void createRecordingDirectory() throws IOException {
        recordingDirectory = new File(context.getExternalFilesDir(null), "Recordings");
        if (!recordingDirectory.exists() && !recordingDirectory.mkdirs()) {
            throw new IOException("Failed to create recordings directory");
        }
    }

    private void setupMediaRecorder() throws IOException {
        mediaRecorder = new MediaRecorder();

        // Configure MediaRecorder
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        // Set audio parameters
        mediaRecorder.setAudioSamplingRate(config.getSampleRate());
        mediaRecorder.setAudioChannels(config.getChannels());
        mediaRecorder.setAudioEncodingBitRate(config.getBitrate());

        // Generate output file path
        long timestamp = System.currentTimeMillis();
        String fileName = String.format("recording_%d.m4a", timestamp);
        File outputFile = new File(recordingDirectory, fileName);
        currentRecordingPath = outputFile.getAbsolutePath();

        mediaRecorder.setOutputFile(currentRecordingPath);

        // Set max file size if specified
        if (config.getMaxDuration() != null && config.getMaxDuration() > 0) {
            // Estimate max file size based on duration and bitrate
            long estimatedSize = (long) ((config.getBitrate() * config.getMaxDuration() * 1.2) / 8) + (1024 * 1024);
            mediaRecorder.setMaxFileSize(estimatedSize);
        }

        // Prepare MediaRecorder
        mediaRecorder.prepare();

        Log.d(TAG, String.format("MediaRecorder configured - Sample Rate: %d, Channels: %d, Bitrate: %d, Output: %s",
               config.getSampleRate(), config.getChannels(), config.getBitrate(), currentRecordingPath));
    }

    /**
     * Custom exception for recording operations
     */
    public static class AudioRecordingException extends Exception {
        private final AudioEngineError errorCode;

        public AudioRecordingException(AudioEngineError errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }

        public AudioRecordingException(AudioEngineError errorCode, String details) {
            super(errorCode.getDetailedMessage(details));
            this.errorCode = errorCode;
        }

        public AudioRecordingException(AudioEngineError errorCode, String details, Throwable cause) {
            super(errorCode.getDetailedMessage(details), cause);
            this.errorCode = errorCode;
        }

        public AudioEngineError getErrorCode() {
            return errorCode;
        }
    }
}
