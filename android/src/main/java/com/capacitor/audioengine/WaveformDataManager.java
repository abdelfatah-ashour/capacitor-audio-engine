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
    private static final int DEBOUNCE_TIME_MS = 50; // Default debounce time of 50ms for ~20fps
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int BUFFER_SIZE_FACTOR = 2; // Reduced from 4 to prevent excessive buffering
    private static final int MIN_DEBOUNCE_MS = 20; // Minimum debounce time to prevent excessive emissions

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

    // Gain factor for audio level amplification (matching iOS accuracy)
    private float gainFactor = 20.0f; // Increased to match iOS defaultGainFactor for better accuracy

    // Peak limiting configuration (matching iOS behavior) - always enabled internally
    private float peakLimit = 0.7f; // Soft peak limit for typical speech levels (matching iOS)
    // Silence gate is always enabled for cleaner waveform visualization (matching iOS behavior)

    // Level calibration and precision control
    private static final float LEVEL_PRECISION = 0.001f; // 3 decimal places precision (matching iOS)
    private static final int MAX_LEVEL_HISTORY = 10; // Track recent levels for calibration
    private float[] recentLevels = new float[MAX_LEVEL_HISTORY]; // Recent level history for calibration
    private int levelIndex = 0; // Index for circular buffer
    private float levelCalibrationFactor = 1.0f; // Dynamic calibration factor
    private boolean levelCalibrated = false; // Whether levels have been calibrated

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

    // Emission tracking for debugging
    private long lastEmissionTime = 0;
    private int emissionCount = 0;
    private long monitoringStartTime = 0;

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
        } else {
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
        } else {
            this.sampleRate = DEFAULT_SAMPLE_RATE;
        }
    }

    /**
     * Configure waveform settings including debounce time
     * @param debounceInSeconds Debounce time in seconds (0.01 to 3600.0 seconds)
     * @param bars Number of bars in the waveform (1 to 256, optional, default: current value)
     */
    public void configureWaveform(float debounceInSeconds, int bars) {
        // Validate and set debounce time with minimum threshold
        if (debounceInSeconds >= 0.01f && debounceInSeconds <= 3600.0f) {
            int requestedMs = Math.round(debounceInSeconds * 1000);
            // Ensure debounce time never goes below minimum threshold
            this.debounceTimeMs = Math.max(MIN_DEBOUNCE_MS, requestedMs);
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
    }

    /**
     * Enable or disable Voice Activity Detection (VAD)
     * @param enabled Enable VAD for more accurate speech detection
     */
    public void setVadEnabled(boolean enabled) {
        this.vadEnabled = enabled;

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

        float optimalGain = 15.0f;

        if (sampleRate >= 48000) {
            optimalGain = 30.0f;
        }


        this.gainFactor = optimalGain;


        // Reset calibration with new settings
        if (speechOnlyMode) {
            resetVadState();
        }
        resetLevelCalibration();
    }

    /**
     * Reset level calibration state for new recording configuration
     */
    private void resetLevelCalibration() {
        levelCalibrated = false;
        levelCalibrationFactor = 1.0f;
        levelIndex = 0;

        // Clear recent levels history
        for (int i = 0; i < MAX_LEVEL_HISTORY; i++) {
            recentLevels[i] = 0.0f;
        }

    }

    /**
     * Calibrate levels based on recent audio characteristics and recording options
     * This ensures consistent level output regardless of recording configuration
     */
    private void calibrateLevels(float currentLevel) {
        // Track recent levels for calibration
        recentLevels[levelIndex] = currentLevel;
        levelIndex = (levelIndex + 1) % MAX_LEVEL_HISTORY;

        // Wait until we have enough samples for calibration
        if (levelIndex == 0 && !levelCalibrated) {
            // Calculate average level from recent samples
            float avgLevel = 0.0f;
            int validSamples = 0;

            for (float level : recentLevels) {
                if (level > 0.0f) {
                    avgLevel += level;
                    validSamples++;
                }
            }

            if (validSamples > 0) {
                avgLevel /= validSamples;

                // Calculate calibration factor to normalize levels
                // Target: typical speech should reach 0.5-0.7 range
                float targetLevel = 0.6f; // Ideal speech level
                if (avgLevel > 0.0f) {
                    levelCalibrationFactor = targetLevel / avgLevel;
                    // Clamp calibration factor to reasonable bounds
                    levelCalibrationFactor = Math.max(0.5f, Math.min(2.0f, levelCalibrationFactor));
                }

                levelCalibrated = true;
            }
        }
    }

    /**
     * Round level to proper precision and apply calibration
     * This eliminates floating-point precision errors like 0.699999988079071
     */
    private float roundAndCalibrateLevel(float level) {
        // Apply level calibration if available
        if (levelCalibrated && level > 0.0f) {
            level *= levelCalibrationFactor;
        }

        // Round to 3 decimal places (matching iOS precision)
        level = Math.round(level / LEVEL_PRECISION) * LEVEL_PRECISION;

        // Ensure level is within valid range
        level = Math.max(0.0f, Math.min(1.0f, level));

        return level;
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
        // Reset level calibration when VAD state changes
        resetLevelCalibration();
    }

    /**
     * Start waveform data monitoring
     * This should be called when recording starts
     */
    public void startMonitoring() {
        if (isActive.get()) {
            return;
        }
        // Reset level calibration for new monitoring session
        resetLevelCalibration();

        // Initialize emission tracking
        monitoringStartTime = System.currentTimeMillis();
        emissionCount = 0;
        lastEmissionTime = 0;

        // Calculate buffer size with debounce-aware sizing
        int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return;
        }

        // Calculate optimal buffer size based on debounce time and sample rate
        // This ensures we don't process audio data more frequently than needed
        int samplesPerDebounce = (sampleRate * debounceTimeMs) / 1000;
        int optimalBufferSize = Math.max(minBufferSize, samplesPerDebounce * 2); // 2 bytes per 16-bit sample

        // Apply buffer size factor but cap it to prevent excessive buffering
        int bufferSize = Math.min(optimalBufferSize, minBufferSize * BUFFER_SIZE_FACTOR);
        this.bufferSize = bufferSize;


        // Initialize AudioRecord for PCM data access
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return;
        }

        isActive.set(true);
        startRecordingThread();

        // Emit waveform init event
        emitWaveformInit();

    }

    /**
     * Stop waveform data monitoring
     */
    public void stopMonitoring() {
        if (!isActive.get()) {
            return;
        }
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
                Thread.currentThread().interrupt();
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
    }

    /**
     * Pause waveform data monitoring
     */
    public void pauseMonitoring() {
        isRecording.set(false);
    }

    /**
     * Resume waveform data monitoring
     */
    public void resumeMonitoring() {
        if (isActive.get()) {
            isRecording.set(true);
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
                while (isActive.get() && !Thread.currentThread().isInterrupted()) {
                    if (!isRecording.get()) {
                        // Paused state - sleep longer to reduce CPU usage
                        Thread.sleep(debounceTimeMs);
                        continue;
                    }

                    int samplesRead = audioRecord.read(buffer, 0, buffer.length);

                    if (samplesRead > 0) {
                        long currentTime = System.currentTimeMillis();

                        // Only process and emit if enough time has passed since last emission
                        if (currentTime - lastEmissionTime >= debounceTimeMs) {
                            processAndEmitWaveformData(buffer, samplesRead);
                            lastEmissionTime = currentTime;
                        } else {
                            // Skip processing this buffer if we're not ready to emit
                            // This prevents unnecessary CPU usage between emissions
                        }
                    } else if (samplesRead < 0) {
                        break;
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            }
        }, "WaveformDataThread");

        recordingThread.start();
    }

    /**
     * Process PCM audio data and emit single waveform level with speech detection
     * Processing order (matching iOS exactly):
     * 1. Calculate RMS with double precision
     * 2. Normalize to 0-1 range
     * 3. Apply voice band filtering (if enabled) - BEFORE gain
     * 4. Apply gain factor - AFTER filtering
     * 5. Apply speech detection (if enabled)
     * 6. Apply global silence gate and soft peak clamp
     *
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     */
    private void processAndEmitWaveformData(short[] buffer, int samplesRead) {
        // Calculate RMS (Root Mean Square) for the entire buffer with higher precision
        double sum = 0.0;

        for (int i = 0; i < samplesRead; i++) {
            double sample = buffer[i];
            sum += sample * sample;
        }

        // Calculate RMS with double precision for better accuracy (matching iOS)
        double rms = Math.sqrt(sum / (double) samplesRead);

        // Normalize to 0-1 range with better precision (matching iOS Float32 approach)
        float rawLevel = (float) (rms / Short.MAX_VALUE);

        // Apply human voice band filtering FIRST (matching iOS order) - before gain
        if (voiceBandFilterEnabled) {
            rawLevel = applyVoiceBandFilter(rawLevel, buffer, samplesRead);
        }

        // Apply configurable gain factor (matching iOS gain factors) - after filtering
        float calculatedLevel = rawLevel * gainFactor;

        // Calibrate levels based on recording configuration and recent audio characteristics
        calibrateLevels(calculatedLevel);

        // Increment frame count for speech detection
        frameCount++;

        // Determine final emit level for lambda
        final float finalEmitLevel;
        if (speechOnlyMode) {
            boolean isSpeech = detectSpeech(calculatedLevel, buffer, samplesRead);
            finalEmitLevel = isSpeech ? calculatedLevel : 0.0f; // Send level = 0 for silence instead of omitting
        } else {
            finalEmitLevel = calculatedLevel;
        }

        // Apply global silence gate and soft peak clamp (matching iOS behavior exactly)
        float emitLevel = finalEmitLevel;

        // Silence gate: zero out very small values even if speechOnlyMode is off (matching iOS)
        float silenceGate = Math.max(0.01f, speechThreshold);
        if (emitLevel < silenceGate) {
            emitLevel = 0.0f;
        }

        // Soft clamp to keep typical speaking levels within ~0.3-0.7 range (matching iOS)
        // (still allows lower values if speaking softly and won't exceed 0.7 peak)
        if (emitLevel > 0.0f) {
            emitLevel = Math.min(peakLimit, emitLevel);
        }

        // Apply final level calibration and rounding to eliminate precision errors
        float originalLevel = emitLevel;
        emitLevel = roundAndCalibrateLevel(emitLevel);

        // Always emit the level value on the main thread (continuous emission)
        final float finalEmitLevelWithGate = emitLevel;

        emissionCount++;

        mainHandler.post(() -> emitWaveformLevel(finalEmitLevelWithGate));
    }

    /**
     * Detect speech in the audio buffer using threshold and optional VAD
     * @param level Normalized RMS level
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples read
     * @return true if speech is detected
     */
    private boolean detectSpeech(float level, short[] buffer, int samplesRead) {
        // Calibrate background noise level for the first few frames (matching iOS precision)
        if (!backgroundNoiseCalibrated && noiseCalibrationFrames < NOISE_CALIBRATION_DURATION) {
            backgroundNoiseLevel += level;
            noiseCalibrationFrames++;

            if (noiseCalibrationFrames == NOISE_CALIBRATION_DURATION) {
                backgroundNoiseLevel = backgroundNoiseLevel / (float) NOISE_CALIBRATION_DURATION;
                // Safety margin matching iOS backgroundNoiseMultiplier
                backgroundNoiseLevel = backgroundNoiseLevel * 1.2f;
                backgroundNoiseCalibrated = true;
            }
            return false; // Don't detect speech during calibration
        }

        // Use adaptive threshold based on background noise if calibrated (matching iOS precision)
        float effectiveThreshold = speechThreshold;
        if (backgroundNoiseCalibrated) {
            // Use the higher of configured threshold or background noise + smaller margin (matching iOS)
            effectiveThreshold = Math.max(speechThreshold, backgroundNoiseLevel + 0.005f);
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

        // Calibrate background noise level (first 30 frames) - matching iOS precision
        if (!backgroundCalibrated && frameCount <= 30) {
            backgroundEnergyLevel += currentLevel;
            if (frameCount == 30) {
                backgroundEnergyLevel = (backgroundEnergyLevel / 30.0f) * ENERGY_THRESHOLD_MULTIPLIER;
                backgroundCalibrated = true;
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

        // Count recent frames above energy threshold (matching iOS precision)
        int speechFrames = 0;
        for (float energy : recentEnergyLevels) {
            if (energy > backgroundEnergyLevel) {
                speechFrames++;
            }
        }

        float speechRatio = (float) speechFrames / (float) vadWindowSize; // Use configurable window size with better precision
        boolean continuityCheck = speechRatio >= VAD_SPEECH_RATIO;

        // Combined VAD decision
        boolean isSpeech = energyCheck && zcrCheck && continuityCheck;

        return isSpeech;
    }

    /**
     * Apply human voice band filtering for noise rejection (matching iOS precision)
     * Uses spectral analysis to emphasize human voice frequencies (85Hz-3400Hz)
     * @param level Current audio level (raw, before gain)
     * @param buffer PCM audio buffer
     * @param samplesRead Number of samples
     * @return Filtered audio level (raw, before gain)
     */
    private float applyVoiceBandFilter(float level, short[] buffer, int samplesRead) {
        try {
            // Enhanced frequency domain analysis using zero-crossing rate (matching iOS approach)
            int zeroCrossings = calculateZeroCrossingRate(buffer, samplesRead);

            // Calculate expected zero-crossing rate for human voice at current sample rate
            // Human voice fundamental: 85-255Hz (male), 165-265Hz (female)
            // Expected ZCR for voice: ~170-530 crossings per second at 44.1kHz
            double expectedMinZCR = (MIN_VOICE_FREQ * 2.0 * samplesRead) / (double) sampleRate;
            double expectedMaxZCR = (MAX_VOICE_FREQ * 2.0 * samplesRead) / (double) sampleRate;

            // Apply band-pass filtering based on ZCR with better precision
            boolean inVoiceBand = zeroCrossings >= expectedMinZCR && zeroCrossings <= expectedMaxZCR;

            if (!inVoiceBand) {
                // Attenuate signals outside voice band (likely noise/music) - matching iOS outOfBandAttenuation
                level *= 0.3f; // Reduce to 30% for non-voice signals (same as iOS)
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
            data.put("timestamp", System.currentTimeMillis());

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
            data.put("peakLimit", peakLimit); // Always 0.7 for iOS consistency
            data.put("levelCalibrated", levelCalibrated); // Level calibration status
            data.put("levelCalibrationFactor", levelCalibrationFactor); // Current calibration factor

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
     * Get current level calibration status for debugging
     * @return String representation of calibration status
     */
    public String getCalibrationStatus() {
        return String.format("LevelCalibrated: %b, Factor: %.3f, PeakLimit: %.3f, GainFactor: %.1f",
            levelCalibrated, levelCalibrationFactor, peakLimit, gainFactor);
    }

    /**
     * Get current emission statistics for debugging
     * @return String representation of emission statistics
     */
    public String getEmissionStats() {
        if (monitoringStartTime == 0) {
            return "Monitoring not started";
        }

        long elapsed = System.currentTimeMillis() - monitoringStartTime;
        float emissionsPerSecond = elapsed > 0 ? (emissionCount * 1000.0f) / elapsed : 0.0f;
        float expectedRate = 1000.0f / debounceTimeMs;

        return String.format("Emissions: %d, Elapsed: %dms, Actual: %.1f/sec, Expected: %.1f/sec, Debounce: %dms",
            emissionCount, elapsed, emissionsPerSecond, expectedRate, debounceTimeMs);
    }

    /**
     * Clean up resources
     */
    public void cleanup() {
        stopMonitoring();
    }
}
