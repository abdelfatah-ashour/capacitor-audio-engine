package com.capacitor.audioengine;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

import com.getcapacitor.JSObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WaveLevelEmitter handles real-time audio level monitoring during recording
 * Simplified implementation focused on emitting normalized wave levels (0.0-1.0)
 * at configurable intervals for UI visualization and speaker activity detection.
 * Features:
 * - Real-time RMS-based audio level calculation
 * - Configurable emission frequency (50-500ms, default 1000ms)
 * - Normalized amplitude values between 0.0 and 1.0
 * - Optimized for human speech detection (-50dB to -10dB range)
 * - Gentle compression curve for better visual representation
 * - Cross-platform consistency with iOS implementation
 * - Low CPU overhead with efficient background processing
 */
public class WaveLevelEmitter {
    private static final String TAG = "WaveLevelEmitter";

    // Configuration constants
    private static final int DEFAULT_EMISSION_INTERVAL_MS = 1000; // Default 1000ms as per SRS
    private static final int MIN_EMISSION_INTERVAL_MS = 50; // Minimum 50ms as per SRS
    private static final int MAX_EMISSION_INTERVAL_MS = 500; // Maximum 500ms as per SRS
    private static final int DEFAULT_SAMPLE_RATE = AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 2;

    private static final float SILENCE_THRESHOLD = 0.001f; // Match iOS silence threshold

    // Recording components
    private AudioRecord audioRecord;
    private Thread processingThread;
    private int bufferSize;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    // Configuration
    private int emissionIntervalMs = DEFAULT_EMISSION_INTERVAL_MS;
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    private final Handler mainHandler;

    // Event callback
    private final EventCallback eventCallback;

    // Emission tracking
    private long lastEmissionTime = 0;

    /**
     * Interface for wave level emission callbacks
     */
    public interface EventCallback {
        void notifyListeners(String eventName, JSObject data);
    }

    /**
     * Constructor for WaveLevelEmitter
     * @param eventCallback Callback for emitting wave level events
     */
    public WaveLevelEmitter(EventCallback eventCallback) {
        this.eventCallback = eventCallback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "WaveLevelEmitter initialized");
    }

    /**
     * Configure emission interval (frequency of wave level updates)
     * @param intervalMs Emission interval in milliseconds (50-500ms, default: 1000ms)
     */
    public void setEmissionInterval(int intervalMs) {
        if (intervalMs >= MIN_EMISSION_INTERVAL_MS && intervalMs <= MAX_EMISSION_INTERVAL_MS) {
            this.emissionIntervalMs = intervalMs;
            Log.d(TAG, "Emission interval set to: " + intervalMs + "ms");
        } else {
            Log.w(TAG, "Invalid emission interval: " + intervalMs + "ms. Must be between " +
                  MIN_EMISSION_INTERVAL_MS + "-" + MAX_EMISSION_INTERVAL_MS + "ms");
        }
    }

    /**
     * Configure the sample rate to match recording settings
     * @param sampleRate Sample rate in Hz (8000-96000)
     */
    public void setSampleRate(int sampleRate) {
        if (sampleRate >= 8000 && sampleRate <= 96000) {
            this.sampleRate = sampleRate;
            Log.d(TAG, "Sample rate set to: " + sampleRate + "Hz");
        } else {
            this.sampleRate = DEFAULT_SAMPLE_RATE;
            Log.w(TAG, "Invalid sample rate: " + sampleRate + "Hz. Using default: " + DEFAULT_SAMPLE_RATE + "Hz");
        }
    }

    /**
     * Start wave level monitoring
     * This should be called when recording starts
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public void startMonitoring() {
        if (isActive.get()) {
            Log.w(TAG, "Monitoring already active");
            return;
        }

        Log.d(TAG, "Starting wave level monitoring with interval: " + emissionIntervalMs + "ms");

        // Calculate buffer size
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Failed to get minimum buffer size");
            emitError("Failed to initialize audio recording");
            return;
        }

        // Calculate optimal buffer size based on emission interval
        int samplesPerInterval = (sampleRate * emissionIntervalMs) / 1000;
        int optimalBufferSize = Math.max(minBufferSize, samplesPerInterval * 2); // 2 bytes per 16-bit sample
        this.bufferSize = Math.min(optimalBufferSize, minBufferSize * BUFFER_SIZE_FACTOR);

        // Initialize AudioRecord
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Failed to initialize AudioRecord");
            emitError("Failed to initialize audio recording");
            return;
        }

        isActive.set(true);
        isRecording.set(true);
        lastEmissionTime = 0;

        startProcessingThread();
        emitInit();
    }

    /**
     * Stop wave level monitoring
     */
    public void stopMonitoring() {
        if (!isActive.get()) {
            return;
        }

        Log.d(TAG, "Stopping wave level monitoring");

        emitDestroy();

        isActive.set(false);
        isRecording.set(false);

        // Stop processing thread
        if (processingThread != null && processingThread.isAlive()) {
            processingThread.interrupt();
            try {
                processingThread.join(1000);
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for processing thread to stop");
            }
        }

        // Release AudioRecord
        if (audioRecord != null) {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
            audioRecord.release();
            audioRecord = null;
        }

        Log.d(TAG, "Wave level monitoring stopped");
    }

    /**
     * Pause wave level monitoring
     */
    public void pauseMonitoring() {
        if (isActive.get()) {
            isRecording.set(false);
            Log.d(TAG, "Wave level monitoring paused");
        }
    }

    /**
     * Resume wave level monitoring
     */
    public void resumeMonitoring() {
        if (isActive.get()) {
            isRecording.set(true);
            Log.d(TAG, "Wave level monitoring resumed");
        }
    }

    /**
     * Check if monitoring is currently active
     */
    public boolean isMonitoring() {
        return isActive.get() && isRecording.get();
    }

    /**
     * Start the processing thread for audio level calculation
     */
    private void startProcessingThread() {
        processingThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            short[] buffer = new short[bufferSize / 2]; // 16-bit samples (2 bytes per sample)

            try {
                audioRecord.startRecording();
                Log.d(TAG, "AudioRecord started, buffer size: " + bufferSize);

                while (isActive.get() && !Thread.currentThread().isInterrupted()) {
                    if (!isRecording.get()) {
                        Thread.sleep(50);
                        continue;
                    }

                    int samplesRead = audioRecord.read(buffer, 0, buffer.length);
                    if (samplesRead > 0) {
                        processAudioBuffer(buffer, samplesRead);
                    }
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "Processing thread interrupted");
            } catch (Exception e) {
                Log.e(TAG, "Error in processing thread", e);
                mainHandler.post(() -> emitError("Audio processing error: " + e.getMessage()));
            } finally {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            }
        }, "WaveLevelProcessingThread");

        processingThread.start();
    }

    /**
     * Process audio buffer and emit wave level at configured intervals
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     */
    private void processAudioBuffer(short[] buffer, int samplesRead) {
        // Check if it's time to emit a level
        long currentTime = System.currentTimeMillis();
        if (lastEmissionTime != 0 && (currentTime - lastEmissionTime) < emissionIntervalMs) {
            return;
        }

        lastEmissionTime = currentTime;

        // Calculate RMS in normalized float domain [-1.0, 1.0] to match iOS
        double sumSquares = 0.0;
        for (int i = 0; i < samplesRead; i++) {
            // 32768.0f to ensure 32767 maps within [-1,1)
            float sample = buffer[i] / 32768.0f;
            sumSquares += sample * sample;
        }
        float rms = (float) Math.sqrt(sumSquares / Math.max(1, samplesRead));

        // Convert RMS to dB - optimized for human speech
        // Human speech typically ranges from -40dB to -10dB
        // Using -50dB to -10dB provides better sensitivity for normal conversation
        final float minDb = -50.0f;  // Adjusted for human speech (was -60dB)
        final float maxDb = -10.0f;  // Adjusted for human speech (was 0dB)
        float rmsDb = 20.0f * (float) Math.log10(rms + 1e-8f); // avoid log(0)

        // Normalize to 0..1 range (minDb -> 0, maxDb -> 1)
        float normalized = (rmsDb - minDb) / (maxDb - minDb);
        normalized = Math.max(0.0f, Math.min(1.0f, normalized));

        // Apply silence threshold after normalization
        if (normalized < SILENCE_THRESHOLD) {
            normalized = 0.0f;
        }

        // Apply gentle compression curve for better visual representation
        // This makes lower levels more visible while preserving dynamic range
        normalized = (float) Math.pow(normalized, 0.7);

        // Emit the level on main thread
        final float finalLevel = normalized;
        mainHandler.post(() -> emitWaveLevel(finalLevel, currentTime));
    }

    /**
     * Emit wave level event to JavaScript
     * @param level Normalized amplitude level (0.0-1.0)
     * @param timestamp Timestamp in milliseconds
     */
    private void emitWaveLevel(float level, long timestamp) {
        try {
            JSObject data = new JSObject();
            data.put("level", level);
            data.put("timestamp", timestamp);
            eventCallback.notifyListeners("waveLevel", data);
        } catch (Exception e) {
            Log.e(TAG, "Error emitting wave level", e);
        }
    }

    /**
     * Emit initialization event
     */
    private void emitInit() {
        try {
            JSObject data = new JSObject();
            data.put("status", "initialized");
            data.put("emissionInterval", emissionIntervalMs);
            data.put("sampleRate", sampleRate);
            eventCallback.notifyListeners("waveLevelInit", data);
            Log.d(TAG, "Wave level monitoring initialized");
        } catch (Exception e) {
            Log.e(TAG, "Error emitting init event", e);
        }
    }

    /**
     * Emit destroy event
     */
    private void emitDestroy() {
        try {
            JSObject data = new JSObject();
            data.put("status", "destroyed");
            data.put("reason", "stop_recording");
            eventCallback.notifyListeners("waveLevelDestroy", data);
            Log.d(TAG, "Wave level monitoring destroyed: " + "stop_recording");
        } catch (Exception e) {
            Log.e(TAG, "Error emitting destroy event", e);
        }
    }

    /**
     * Emit error event
     * @param message Error message
     */
    private void emitError(String message) {
        try {
            JSObject data = new JSObject();
            data.put("error", message);
            eventCallback.notifyListeners("waveLevelError", data);
            Log.e(TAG, "Wave level error: " + message);
        } catch (Exception e) {
            Log.e(TAG, "Error emitting error event", e);
        }
    }

    /**
     * Clean up resources
     */
    public void cleanup() {
        stopMonitoring();
    }
}