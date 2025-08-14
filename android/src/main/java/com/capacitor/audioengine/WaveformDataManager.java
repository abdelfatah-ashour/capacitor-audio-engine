package com.capacitor.audioengine;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WaveformDataManager handles real-time audio level monitoring during recording
 * Features:
 * - Real-time PCM audio data processing
 * - Incremental audio level emission for growing waveform visualization
 * - Normalized amplitude values between 0 and 1
 * - Low CPU overhead with RMS calculation
 * - Emission frequency: ~20-50ms for smooth UI animations
 */
public class WaveformDataManager {
    private static final String TAG = "WaveformDataManager";

    // Configuration constants
    private static final int DEFAULT_BARS = 32;
    private static final int EMISSION_INTERVAL_MS = 50; // 20fps for continuous emission (as requested)
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 4; // Multiply minimum buffer size for stability

    // Speech detection constants
    private static final float DEFAULT_SPEECH_THRESHOLD = 0.02f; // Amplitude threshold for speech detection
    private static final int VAD_WINDOW_SIZE = 10; // Number of frames to analyze for VAD
    private static final float VAD_SPEECH_RATIO = 0.3f; // Minimum ratio of speech frames in window
    private static final float ENERGY_THRESHOLD_MULTIPLIER = 2.0f; // Energy threshold multiplier for VAD

    // Recording components
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private int bufferSize;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    // Configuration
    private int numberOfBars = DEFAULT_BARS;
    private int emissionIntervalMs = EMISSION_INTERVAL_MS; // Configurable emission interval
    private final Handler mainHandler;

    // Speech detection configuration
    private boolean speechOnlyMode = false;
    private float speechThreshold = DEFAULT_SPEECH_THRESHOLD;
    private boolean vadEnabled = false;
    private int backgroundCalibrationDuration = 1000; // Default 1 second

    // VAD state tracking
    private final float[] recentEnergyLevels = new float[VAD_WINDOW_SIZE];
    private int energyIndex = 0;
    private float backgroundEnergyLevel = 0.0f;
    private boolean backgroundCalibrated = false;
    private int frameCount = 0;

    // Event callback
    private final EventCallback eventCallback;

    /**
     * Interface for waveform data emission callbacks
     */
    public interface EventCallback {
        void notifyListeners(String eventName, JSObject data);
    }

    /**
     * Constructor for WaveformDataManager
     * @param eventCallback Callback for emitting waveform data events
     */
    public WaveformDataManager(EventCallback eventCallback) {
        this.eventCallback = eventCallback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Configure the number of waveform bars
     * @param bars Number of bars in the waveform (default: 32)
     */
    public void setNumberOfBars(int bars) {
        if (bars > 0 && bars <= 256) { // Reasonable limits
            this.numberOfBars = bars;
            Log.d(TAG, "Number of waveform bars set to: " + bars);
        } else {
            Log.w(TAG, "Invalid number of bars: " + bars + ", using default: " + DEFAULT_BARS);
            this.numberOfBars = DEFAULT_BARS;
        }
    }

    /**
     * Configure waveform settings including emission interval
     * @param debounceInSeconds Emission interval in seconds (0.01 to 3600.0 seconds)
     * @param bars Number of bars in the waveform (1 to 256, optional, default: current value)
     */
    public void configureWaveform(float debounceInSeconds, int bars) {
        // Validate and set emission interval
        if (debounceInSeconds >= 0.01f && debounceInSeconds <= 3600.0f) {
            this.emissionIntervalMs = Math.round(debounceInSeconds * 1000);
            Log.d(TAG, "Emission interval set to: " + debounceInSeconds + " seconds (" + this.emissionIntervalMs + "ms)");
        } else {
            Log.w(TAG, "Invalid emission interval: " + debounceInSeconds + " seconds, keeping current: " + (this.emissionIntervalMs / 1000.0f) + " seconds");
        }

        // Validate and set number of bars if provided
        if (bars > 0) {
            setNumberOfBars(bars);
        }
    }

    /**
     * Configure waveform settings with emission interval only
     * @param debounceInSeconds Emission interval in seconds (0.01 to 3600.0 seconds)
     */
    public void configureWaveform(float debounceInSeconds) {
        configureWaveform(debounceInSeconds, -1); // -1 means don't change bars
    }

    /**
     * Configure speech-only mode with threshold filtering
     * @param enabled Enable speech-only mode
     * @param threshold Amplitude threshold for speech detection (0.0-1.0)
     */
    public void setSpeechOnlyMode(boolean enabled, float threshold) {
        this.speechOnlyMode = enabled;
        this.speechThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        Log.d(TAG, "Speech-only mode: " + enabled + ", threshold: " + this.speechThreshold);

        if (enabled) {
            // Reset VAD state when enabling speech mode
            resetVadState();
        }
    }

    /**
     * Enable or disable Voice Activity Detection (VAD)
     * @param enabled Enable VAD for more accurate speech detection
     */
    public void setVadEnabled(boolean enabled) {
        this.vadEnabled = enabled;
        Log.d(TAG, "VAD enabled: " + enabled);

        if (enabled) {
            resetVadState();
        }
    }

    /**
     * Configure speech detection with all parameters in one call
     * @param enabled Enable speech-only detection
     * @param threshold Amplitude threshold for speech detection (0.0-1.0)
     * @param useVAD Enable Voice Activity Detection for more accurate speech detection
     * @param calibrationDuration Background noise calibration duration in milliseconds
     */
    public void configureSpeechDetection(boolean enabled, float threshold, boolean useVAD, int calibrationDuration) {
        this.speechOnlyMode = enabled;
        this.speechThreshold = Math.max(0.0f, Math.min(1.0f, threshold));
        this.vadEnabled = useVAD;
        this.backgroundCalibrationDuration = calibrationDuration;

        Log.d(TAG, "Speech detection configured - enabled: " + enabled +
             ", threshold: " + this.speechThreshold + ", VAD: " + useVAD +
             ", calibration: " + calibrationDuration + "ms");

        if (enabled || useVAD) {
            resetVadState();
        }
    }

    /**
     * Reset Voice Activity Detection state
     */
    private void resetVadState() {
        frameCount = 0;
        energyIndex = 0;
        backgroundEnergyLevel = 0.0f;
        backgroundCalibrated = false;
        for (int i = 0; i < recentEnergyLevels.length; i++) {
            recentEnergyLevels[i] = 0.0f;
        }
    }

    /**
     * Start waveform data monitoring
     * This should be called when recording starts
     */
    public void startMonitoring() {
        if (isActive.get()) {
            Log.w(TAG, "Waveform monitoring is already active");
            return;
        }

        try {
            // Calculate buffer size
            int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Failed to get minimum buffer size");
                return;
            }

            int bufferSize = minBufferSize * BUFFER_SIZE_FACTOR;
            this.bufferSize = bufferSize;
            Log.d(TAG, "Starting waveform monitoring with buffer size: " + bufferSize);

            // Initialize AudioRecord for PCM data access
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed");
                return;
            }

            isActive.set(true);
            startRecordingThread();

            // Emit waveform init event
            emitWaveformInit();

            Log.d(TAG, "Waveform monitoring started successfully");

        } catch (SecurityException e) {
            Log.e(TAG, "Permission denied for audio recording", e);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start waveform monitoring", e);
        }
    }

    /**
     * Stop waveform data monitoring
     */
    public void stopMonitoring() {
        if (!isActive.get()) {
            Log.d(TAG, "Waveform monitoring is not active");
            return;
        }

        Log.d(TAG, "Stopping waveform monitoring");

        // Emit waveform destroy event before cleanup
        emitWaveformDestroy("stop_recording");

        isActive.set(false);
        isRecording.set(false);

        // Stop recording thread
        if (recordingThread != null && recordingThread.isAlive()) {
            recordingThread.interrupt();
            try {
                recordingThread.join(1000); // Wait up to 1 second
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for recording thread to stop", e);
                Thread.currentThread().interrupt();
            }
        }

        // Release AudioRecord
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
                audioRecord = null;
            } catch (Exception e) {
                Log.w(TAG, "Error releasing AudioRecord", e);
            }
        }

        Log.d(TAG, "Waveform monitoring stopped");
    }

    /**
     * Pause waveform data monitoring
     */
    public void pauseMonitoring() {
        isRecording.set(false);
        Log.d(TAG, "Waveform monitoring paused");
    }

    /**
     * Resume waveform data monitoring
     */
    public void resumeMonitoring() {
        if (isActive.get()) {
            isRecording.set(true);
            Log.d(TAG, "Waveform monitoring resumed");
        }
    }

    /**
     * Check if monitoring is currently active
     */
    public boolean isMonitoring() {
        return isActive.get() && isRecording.get();
    }

    /**
     * Start the recording thread for PCM data processing
     */
    private void startRecordingThread() {
        recordingThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            short[] buffer = new short[bufferSize / 2]; // 16-bit samples (2 bytes per sample)
            long lastEmissionTime = 0;

            try {
                audioRecord.startRecording();
                isRecording.set(true);

                Log.d(TAG, "Recording thread started, buffer size: " + buffer.length);

                while (isActive.get() && !Thread.currentThread().isInterrupted()) {
                    if (!isRecording.get()) {
                        // Paused state - still read data to prevent buffer overflow but don't emit
                        Thread.sleep(10);
                        continue;
                    }

                    int samplesRead = audioRecord.read(buffer, 0, buffer.length);

                    if (samplesRead > 0) {
                        long currentTime = System.currentTimeMillis();

                        // Emit waveform data at the specified interval
                        if (currentTime - lastEmissionTime >= emissionIntervalMs) {
                            processAndEmitWaveformData(buffer, samplesRead);
                            lastEmissionTime = currentTime;
                        }
                    } else if (samplesRead < 0) {
                        Log.e(TAG, "Error reading audio data: " + samplesRead);
                        break;
                    }
                }

            } catch (InterruptedException e) {
                Log.d(TAG, "Recording thread interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.e(TAG, "Error in recording thread", e);
            } finally {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    try {
                        audioRecord.stop();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping AudioRecord in thread", e);
                    }
                }
                Log.d(TAG, "Recording thread finished");
            }
        }, "WaveformDataThread");

        recordingThread.start();
    }

    /**
     * Process PCM audio data and emit single waveform level with speech detection
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     */
    private void processAndEmitWaveformData(short[] buffer, int samplesRead) {
        try {
            // Calculate RMS (Root Mean Square) for the entire buffer
            long sum = 0;

            for (int i = 0; i < samplesRead; i++) {
                long sample = buffer[i];
                sum += sample * sample;
            }

            // Calculate RMS and normalize to 0-1 range
            double rms = Math.sqrt(sum / (double) samplesRead);

            // Apply gain factor to match recorded audio levels (similar to AGC)
            // Android MediaRecorder applies automatic gain, so we boost raw levels to compensate
            float rawLevel = (float) (rms / Short.MAX_VALUE);
            float gainFactor = 8.0f; // Boost to better match final recording levels
            float calculatedLevel = (float) Math.min(1.0, rawLevel * gainFactor);

            // Determine final emit level for lambda
            final float finalEmitLevel;
            if (speechOnlyMode) {
                boolean isSpeech = detectSpeech(calculatedLevel, buffer, samplesRead);
                finalEmitLevel = isSpeech ? calculatedLevel : 0.0f; // Send level = 0 for silence instead of omitting
            } else {
                finalEmitLevel = calculatedLevel;
            }

            // Always emit the level value on the main thread (continuous emission)
            mainHandler.post(() -> emitWaveformLevel(finalEmitLevel));

        } catch (Exception e) {
            Log.e(TAG, "Error processing waveform data", e);
        }
    }

    /**
     * Detect speech in the audio buffer using threshold and optional VAD
     * @param level Normalized RMS level
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     * @return true if speech is detected
     */
    private boolean detectSpeech(float level, short[] buffer, int samplesRead) {
        // Simple threshold check
        boolean aboveThreshold = level > speechThreshold;

        if (!vadEnabled) {
            return aboveThreshold;
        }

        // Enhanced VAD (Voice Activity Detection)
        return performVAD(level, buffer, samplesRead) && aboveThreshold;
    }

    /**
     * Perform Voice Activity Detection using energy and spectral analysis
     * @param currentLevel Current RMS level
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     * @return true if voice activity is detected
     */
    private boolean performVAD(float currentLevel, short[] buffer, int samplesRead) {
        frameCount++;

        // Update energy level history
        recentEnergyLevels[energyIndex] = currentLevel;
        energyIndex = (energyIndex + 1) % VAD_WINDOW_SIZE;

        // Calibrate background noise level (first 30 frames)
        if (!backgroundCalibrated && frameCount <= 30) {
            backgroundEnergyLevel += currentLevel;
            if (frameCount == 30) {
                backgroundEnergyLevel = (backgroundEnergyLevel / 30.0f) * ENERGY_THRESHOLD_MULTIPLIER;
                backgroundCalibrated = true;
                Log.d(TAG, "VAD background energy calibrated: " + backgroundEnergyLevel);
            }
            return false; // Don't emit during calibration
        }

        if (!backgroundCalibrated) {
            return false;
        }

        // Energy-based detection
        boolean energyCheck = currentLevel > backgroundEnergyLevel;

        // Calculate zero crossing rate for additional speech characteristics
        int zeroCrossings = calculateZeroCrossingRate(buffer, samplesRead);
        boolean zcrCheck = zeroCrossings > 10 && zeroCrossings < 1000; // Typical speech range

        // Count recent frames above energy threshold
        int speechFrames = 0;
        for (float energy : recentEnergyLevels) {
            if (energy > backgroundEnergyLevel) {
                speechFrames++;
            }
        }

        float speechRatio = (float) speechFrames / VAD_WINDOW_SIZE;
        boolean continuityCheck = speechRatio >= VAD_SPEECH_RATIO;

        // Combined VAD decision
        boolean isSpeech = energyCheck && zcrCheck && continuityCheck;

        if (frameCount % 100 == 0) { // Log every 100 frames for debugging
            Log.d(TAG, String.format("VAD: energy=%.3f (bg=%.3f), zcr=%d, ratio=%.2f, speech=%b",
                currentLevel, backgroundEnergyLevel, zeroCrossings, speechRatio, isSpeech));
        }

        return isSpeech;
    }

    /**
     * Calculate zero crossing rate for speech detection
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     * @return Zero crossing rate
     */
    private int calculateZeroCrossingRate(short[] buffer, int samplesRead) {
        int crossings = 0;
        for (int i = 1; i < samplesRead; i++) {
            if ((buffer[i] >= 0) != (buffer[i-1] >= 0)) {
                crossings++;
            }
        }
        return crossings;
    }

    /**
     * Emit single waveform level to JavaScript for growing waveform
     * @param level Normalized amplitude level (0-1)
     */
    private void emitWaveformLevel(float level) {
        try {
            JSObject data = new JSObject();
            data.put("level", level);

            eventCallback.notifyListeners("waveformData", data);

        } catch (Exception e) {
            Log.e(TAG, "Error emitting waveform level", e);
        }
    }

    /**
     * Emit waveform initialization event
     */
    private void emitWaveformInit() {
        try {
            JSObject data = new JSObject();
            data.put("numberOfBars", numberOfBars);
            data.put("speechOnlyMode", speechOnlyMode);
            data.put("speechThreshold", speechThreshold);
            data.put("vadEnabled", vadEnabled);
            data.put("calibrationDuration", backgroundCalibrationDuration);

            eventCallback.notifyListeners("waveformInit", data);

        } catch (Exception e) {
            Log.e(TAG, "Error emitting waveform init event", e);
        }
    }

    /**
     * Emit waveform destroy event
     * @param reason Reason for destruction
     */
    private void emitWaveformDestroy(String reason) {
        try {
            JSObject data = new JSObject();
            data.put("reason", reason);
            data.put("timestamp", System.currentTimeMillis());

            eventCallback.notifyListeners("waveformDestroy", data);

        } catch (Exception e) {
            Log.e(TAG, "Error emitting waveform destroy event", e);
        }
    }

    /**
     * Clean up resources
     */
    public void cleanup() {
        stopMonitoring();
    }
}
