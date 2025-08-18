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
    private static final int DEFAULT_BARS = 128; // Default is 128 bars for higher resolution
    private static final int DEBOUNCE_TIME_MS = 1000; // Default debounce time of 1 second
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 4; // Multiply minimum buffer size for stability

    // Speech detection constants
    private static final float DEFAULT_SPEECH_THRESHOLD = 0.01f; // Lowered threshold to match iOS sensitivity
    private static final int DEFAULT_VAD_WINDOW_SIZE = 5; // Reduced for lower latency (~250ms at 20fps)
    private static final int MAX_VAD_WINDOW_SIZE = 20; // Maximum window size for high-latency scenarios
    private static final float VAD_SPEECH_RATIO = 0.3f; // Minimum ratio of speech frames in window
    private static final float ENERGY_THRESHOLD_MULTIPLIER = 2.0f; // Energy threshold multiplier for VAD

    // Human voice frequency band constants for noise rejection
    private static final float MIN_VOICE_FREQ = 85.0f; // Hz - Lowest fundamental frequency for human voice
    private static final float MAX_VOICE_FREQ = 3400.0f; // Hz - Highest frequency for speech intelligibility

    // Recording components
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private int bufferSize;
    private final AtomicBoolean isActive = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    // Configuration
    private int numberOfBars = DEFAULT_BARS;
    private int debounceTimeMs = DEBOUNCE_TIME_MS; // Configurable debounce time
    private int sampleRate = DEFAULT_SAMPLE_RATE; // Configurable sample rate to match recording
    private final Handler mainHandler;

    // Speech detection configuration
    private boolean speechOnlyMode = false;
    private float speechThreshold = DEFAULT_SPEECH_THRESHOLD;
    private boolean vadEnabled = false;
    private int backgroundCalibrationDuration = 1000; // Default 1 second

    private int vadWindowSize = DEFAULT_VAD_WINDOW_SIZE; // Configurable VAD window size for latency optimization
    private boolean voiceBandFilterEnabled = true; // Enable human voice band filtering for noise rejection

    // VAD state tracking (now with configurable window size)
    private float[] recentEnergyLevels; // Will be initialized based on vadWindowSize
    private int energyIndex = 0;
    private float backgroundEnergyLevel = 0.0f;
    private boolean backgroundCalibrated = false;
    private int frameCount = 0;

    // Background noise tracking for non-VAD speech detection
    private float backgroundNoiseLevel = 0.0f;
    private boolean backgroundNoiseCalibrated = false;
    private int noiseCalibrationFrames = 0;
    private static final int NOISE_CALIBRATION_DURATION = 30; // Frames for background noise calibration

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

        // Initialize VAD energy levels array with default size
        this.recentEnergyLevels = new float[vadWindowSize];
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
     * Configure the sample rate to match recording settings
     * @param sampleRate Sample rate in Hz (8000-96000)
     */
    public void setSampleRate(int sampleRate) {
        if (sampleRate >= 8000 && sampleRate <= 96000) {
            this.sampleRate = sampleRate;
            Log.d(TAG, "Sample rate set to: " + sampleRate + " Hz");
        } else {
            Log.w(TAG, "Invalid sample rate: " + sampleRate + ", using default: " + DEFAULT_SAMPLE_RATE);
            this.sampleRate = DEFAULT_SAMPLE_RATE;
        }
    }

    /**
     * Configure waveform settings including debounce time
     * @param debounceInSeconds Debounce time in seconds (0.01 to 3600.0 seconds)
     * @param bars Number of bars in the waveform (1 to 256, optional, default: current value)
     */
    public void configureWaveform(float debounceInSeconds, int bars) {
        // Validate and set debounce time
        if (debounceInSeconds >= 0.01f && debounceInSeconds <= 3600.0f) {
            this.debounceTimeMs = Math.round(debounceInSeconds * 1000);
            Log.d(TAG, "Debounce time set to: " + debounceInSeconds + " seconds (" + this.debounceTimeMs + "ms)");
        } else {
            Log.w(TAG, "Invalid debounce time: " + debounceInSeconds + " seconds, keeping current: " + (this.debounceTimeMs / 1000.0f) + " seconds");
        }

        // Validate and set number of bars if provided
        if (bars > 0) {
            setNumberOfBars(bars);
        }
    }

    /**
     * Configure waveform settings with debounce time only
     * @param debounceInSeconds Debounce time in seconds (0.01 to 3600.0 seconds)
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
     * Configure VAD window size for latency optimization
     * @param windowSize Number of frames to analyze (3-20 frames, default: 5)
     */
    public void setVadWindowSize(int windowSize) {
        int newSize = Math.max(3, Math.min(MAX_VAD_WINDOW_SIZE, windowSize));
        if (newSize != vadWindowSize) {
            vadWindowSize = newSize;
            // Reinitialize the energy levels array
            recentEnergyLevels = new float[vadWindowSize];
            energyIndex = 0;
            Log.d(TAG, "VAD window size set to: " + vadWindowSize + " frames (~" + (vadWindowSize * 50) + "ms latency)");

            // Reset VAD state if currently enabled
            if (vadEnabled) {
                resetVadState();
            }
        }
    }

    /**
     * Enable or disable human voice band filtering for noise rejection
     * @param enabled Enable band-pass filtering for human voice frequencies (85Hz-3400Hz)
     */
    public void setVoiceBandFilterEnabled(boolean enabled) {
        this.voiceBandFilterEnabled = enabled;
        Log.d(TAG, "Voice band filter: " + (enabled ? "ENABLED" : "disabled") +
              " (filtering " + MIN_VOICE_FREQ + "Hz-" + MAX_VOICE_FREQ + "Hz)");
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
     * @param useVAD Enable Voice Activity Detection for more accurate speech detection (default: false)
     * @param calibrationDuration Background noise calibration duration in milliseconds (default: 1000)
     */
    public void configureSpeechDetection(boolean enabled, float threshold, Boolean useVAD, Integer calibrationDuration) {
        this.speechOnlyMode = enabled;
        this.speechThreshold = Math.max(0.0f, Math.min(1.0f, threshold));

        // Handle null values with defaults
        this.vadEnabled = useVAD != null ? useVAD : false;
        this.backgroundCalibrationDuration = calibrationDuration != null ? calibrationDuration : 1000;

        Log.d(TAG, "Speech detection configured - enabled: " + enabled +
             ", threshold: " + this.speechThreshold + ", VAD: " + this.vadEnabled +
             ", calibration: " + this.backgroundCalibrationDuration + "ms" +
             ", window: " + vadWindowSize + " frames" +
             ", voiceFilter: " + voiceBandFilterEnabled);

        if (enabled || this.vadEnabled) {
            resetVadState();
        }
    }

    /**
     * Configure speech detection with all parameters in one call - overload for primitive types
     * @param enabled Enable speech-only detection
     * @param threshold Amplitude threshold for speech detection (0.0-1.0)
     * @param useVAD Enable Voice Activity Detection for more accurate speech detection
     * @param calibrationDuration Background noise calibration duration in milliseconds
     */
    public void configureSpeechDetection(boolean enabled, float threshold, boolean useVAD, int calibrationDuration) {
        configureSpeechDetection(enabled, threshold, Boolean.valueOf(useVAD), Integer.valueOf(calibrationDuration));
    }

    /**
     * Configure advanced VAD settings for optimal performance
     * @param enabled Enable VAD (default: false)
     * @param windowSize VAD window size in frames (3-20, smaller = lower latency) (default: 5)
     * @param enableVoiceFilter Enable human voice band filtering (default: true)
     */
    public void configureAdvancedVAD(Boolean enabled, Integer windowSize, Boolean enableVoiceFilter) {
        // Handle null values with defaults
        boolean vadEnabled = enabled != null ? enabled : false;
        int vadWindowSize = windowSize != null ? windowSize : 5;
        boolean voiceFilter = enableVoiceFilter != null ? enableVoiceFilter : true;

        setVadEnabled(vadEnabled);
        setVadWindowSize(vadWindowSize);
        setVoiceBandFilterEnabled(voiceFilter);

        Log.d(TAG, "Advanced VAD configured - enabled: " + vadEnabled +
             ", window: " + this.vadWindowSize + " frames (~" + (this.vadWindowSize * 50) + "ms)" +
             ", voiceFilter: " + voiceBandFilterEnabled);
    }

    /**
     * Configure advanced VAD settings for optimal performance - overload for primitive types
     * @param enabled Enable VAD
     * @param windowSize VAD window size in frames (3-20, smaller = lower latency)
     * @param enableVoiceFilter Enable human voice band filtering
     */
    public void configureAdvancedVAD(boolean enabled, int windowSize, boolean enableVoiceFilter) {
        configureAdvancedVAD(Boolean.valueOf(enabled), Integer.valueOf(windowSize), Boolean.valueOf(enableVoiceFilter));
    }

    /**
     * Configure waveform manager with recording parameters for optimal performance
     * @param sampleRate Recording sample rate (e.g., 48000)
     * @param channels Number of recording channels (e.g., 2 for stereo)
     * @param speechThreshold Speech detection threshold (0.0-1.0)
     */
    public void configureForRecording(int sampleRate, int channels, float speechThreshold) {
        // Set sample rate to match recording
        setSampleRate(sampleRate);

        // Adjust speech threshold based on recording quality
        // Higher sample rates and stereo recording tend to have better SNR
        float adjustedThreshold = speechThreshold;
        if (sampleRate >= 48000) {
            // For high-quality recordings, we can use a slightly lower threshold
            adjustedThreshold = Math.max(0.01f, speechThreshold * 0.8f);
        }

        this.speechThreshold = adjustedThreshold;

        Log.d(TAG, "Configured for recording - sampleRate: " + sampleRate +
              ", channels: " + channels + ", adjustedThreshold: " + adjustedThreshold);

        // Reset calibration with new settings
        if (speechOnlyMode) {
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

        // Reinitialize energy levels array with current window size
        recentEnergyLevels = new float[vadWindowSize];
        for (int i = 0; i < recentEnergyLevels.length; i++) {
            recentEnergyLevels[i] = 0.0f;
        }

        // Reset background noise calibration
        backgroundNoiseLevel = 0.0f;
        backgroundNoiseCalibrated = false;
        noiseCalibrationFrames = 0;
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
            int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Failed to get minimum buffer size");
                return;
            }

            int bufferSize = minBufferSize * BUFFER_SIZE_FACTOR;
            this.bufferSize = bufferSize;
            Log.d(TAG, "Starting waveform monitoring with buffer size: " + bufferSize + ", sample rate: " + sampleRate);

            // Initialize AudioRecord for PCM data access
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
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

                        // Emit waveform data at the specified debounce interval
                        if (currentTime - lastEmissionTime >= debounceTimeMs) {
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

            // This prevents background noise from being over-amplified
            float rawLevel = (float) (rms / Short.MAX_VALUE);
            float gainFactor = 8.0f; // Increased gain factor for better Android sensitivity
            float calculatedLevel = (float) Math.min(1.0, rawLevel * gainFactor);

            // Apply human voice band filtering if enabled (noise rejection)
            if (voiceBandFilterEnabled) {
                calculatedLevel = applyVoiceBandFilter(calculatedLevel, buffer, samplesRead);
            }

            // Increment frame count for speech detection
            frameCount++;

            // Determine final emit level for lambda
            final float finalEmitLevel;
            if (speechOnlyMode) {
                boolean isSpeech = detectSpeech(calculatedLevel, buffer, samplesRead);
                finalEmitLevel = isSpeech ? calculatedLevel : 0.0f; // Send level = 0 for silence instead of omitting

                // Debug logging for speech detection (always log for debugging)
                if (frameCount % 20 == 0) { // Log every 20 frames for better debugging
                    Log.d(TAG, String.format("Speech detection: level=%.4f, threshold=%.4f, isSpeech=%b, emitLevel=%.4f, frame=%d",
                        calculatedLevel, speechThreshold, isSpeech, finalEmitLevel, frameCount));
                }
            } else {
                finalEmitLevel = calculatedLevel;
                // Log raw levels when not using speech detection
                if (frameCount % 20 == 0) {
                    Log.d(TAG, String.format("Raw audio level: %.4f, mode=NORMAL, frame=%d",
                        calculatedLevel, frameCount));
                }
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
        // Calibrate background noise level for the first few frames
        if (!backgroundNoiseCalibrated && noiseCalibrationFrames < NOISE_CALIBRATION_DURATION) {
            backgroundNoiseLevel += level;
            noiseCalibrationFrames++;

            if (noiseCalibrationFrames == NOISE_CALIBRATION_DURATION) {
                backgroundNoiseLevel = backgroundNoiseLevel / NOISE_CALIBRATION_DURATION;
                // Reduced safety margin to be less aggressive
                backgroundNoiseLevel = backgroundNoiseLevel * 1.2f;
                backgroundNoiseCalibrated = true;
                Log.d(TAG, "Background noise calibrated: " + backgroundNoiseLevel);
            }
            return false; // Don't detect speech during calibration
        }

        // Use adaptive threshold based on background noise if calibrated
        float effectiveThreshold = speechThreshold;
        if (backgroundNoiseCalibrated) {
            // Use the higher of configured threshold or background noise + smaller margin
            effectiveThreshold = Math.max(speechThreshold, backgroundNoiseLevel + 0.005f);
        }

        // Log effective threshold every 100 frames for debugging
        if (frameCount % 100 == 0) {
            Log.d(TAG, String.format("Effective threshold: %.4f (config: %.4f, bg: %.4f, calibrated: %b)",
                effectiveThreshold, speechThreshold, backgroundNoiseLevel, backgroundNoiseCalibrated));
        }

        // Simple threshold check
        boolean aboveThreshold = level > effectiveThreshold;

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
        // Don't increment frameCount here since it's already incremented in processAndEmitWaveformData

        // Update energy level history
        recentEnergyLevels[energyIndex] = currentLevel;
        energyIndex = (energyIndex + 1) % vadWindowSize; // Use configurable window size

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

        float speechRatio = (float) speechFrames / vadWindowSize; // Use configurable window size
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
     * Apply human voice band filtering for noise rejection
     * Uses spectral analysis to emphasize human voice frequencies (85Hz-3400Hz)
     * @param level Current audio level
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples
     * @return Filtered audio level
     */
    private float applyVoiceBandFilter(float level, short[] buffer, int samplesRead) {
        try {
            // Simple frequency domain analysis using zero-crossing rate and energy distribution
            int zeroCrossings = calculateZeroCrossingRate(buffer, samplesRead);

            // Calculate expected zero-crossing rate for human voice at current sample rate
            // Human voice fundamental: 85-255Hz (male), 165-265Hz (female)
            // Expected ZCR for voice: ~170-530 crossings per second at 44.1kHz
            float expectedMinZCR = (MIN_VOICE_FREQ * 2 * samplesRead) / sampleRate;
            float expectedMaxZCR = (MAX_VOICE_FREQ * 2 * samplesRead) / sampleRate;

            // Apply band-pass filtering based on ZCR
            boolean inVoiceBand = zeroCrossings >= expectedMinZCR && zeroCrossings <= expectedMaxZCR;

            if (!inVoiceBand) {
                // Attenuate signals outside voice band (likely noise/music)
                level *= 0.3f; // Reduce to 30% for non-voice signals

                if (frameCount % 100 == 0) { // Debug logging
                    Log.d(TAG, String.format("Voice filter: ZCR=%d (expected: %.1f-%.1f), inBand=%b, level=%.4f",
                        zeroCrossings, expectedMinZCR, expectedMaxZCR, inVoiceBand, level));
                }
            }

            return level;

        } catch (Exception e) {
            Log.w(TAG, "Error in voice band filter, using original level", e);
            return level;
        }
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
            data.put("vadWindowSize", vadWindowSize);
            data.put("vadLatencyMs", vadWindowSize * 50); // Approximate latency in ms
            data.put("voiceBandFilterEnabled", voiceBandFilterEnabled);
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
