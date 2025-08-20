import Foundation
import AVFoundation

/**
 * WaveformDataManager handles real-time audio level monitoring during recording on iOS
 * Features:
 * - Real-time PCM audio data processing using AVAudioEngine
 * - Incremental audio level emission for growing waveform visualization
 * - Normalized amplitude values between 0 and 1
 * - Low CPU overhead with efficient audio tap processing
 * - Continuous emission at 50ms intervals (20fps)
 * - Speech-only detection with configurable threshold filtering
 * - Voice Activity Detection (VAD) with energy analysis and zero-crossing rate
 * - Configurable background noise calibration duration
 * - Automatic hardware format adaptation (supports 44.1kHz, 48kHz, etc.)
 * - Multi-channel audio support with automatic mono conversion
 * - Silence detection with level = 0 emission instead of omitting events
 */
class WaveformDataManager {

    // MARK: - Configuration Constants

    private static let defaultBars = 128 // Default is 128 bars for higher resolution
    private static let debounceTime: TimeInterval = 1.0 // Default debounce time of 1 second
    private static let sampleRate: Double = 44100.0
    private static let bufferSize: AVAudioFrameCount = 1024

    // Speech detection constants
    private static let defaultSpeechThreshold: Float = 0.01 // Aligned with Android threshold
    private static let defaultVadWindowSize = 5 // Reduced for lower latency (~250ms at 20fps)
    private static let maxVadWindowSize = 20 // Maximum window size for high-latency scenarios
    private static let vadSpeechRatio: Float = 0.3 // Minimum ratio of speech frames in window
    private static let energyThresholdMultiplier: Float = 2.0 // Energy threshold multiplier for VAD

    // Human voice frequency band constants for noise rejection
    private static let minVoiceFreq: Float = 85.0 // Hz - Lowest fundamental frequency for human voice
    private static let maxVoiceFreq: Float = 3400.0 // Hz - Highest frequency for speech intelligibility

    // Non-VAD speech detection constants
    private static let adaptiveThresholdFrames = 20 // Frames to calculate adaptive threshold
    private static let minSpeechThreshold: Float = 0.002 // Minimum threshold for very quiet environments
    private static let maxSpeechThreshold: Float = 0.05 // Maximum threshold to prevent false positives

    // MARK: - Properties

    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var numberOfBars: Int = defaultBars
    private var debounceTime: TimeInterval = debounceTime // Configurable debounce time, initialized with default value
    private var isActive: Bool = false
    private var isRecording: Bool = false
    private var lastEmissionTime: TimeInterval = 0

    // Speech detection properties
    private var speechOnlyMode: Bool = false
    private var speechThreshold: Float = defaultSpeechThreshold
    private var vadEnabled: Bool = false
    private var backgroundCalibrationDuration: Int = 1000 // Default 1 second (matching Android)
    private var vadWindowSize: Int = defaultVadWindowSize // Configurable VAD window size for latency optimization
    private var voiceBandFilterEnabled: Bool = true // Enable human voice band filtering for noise rejection
    private var currentSampleRate: Double = sampleRate // Track current sample rate for ZCR-based voice band filter

    // Speech detection calculation properties (aligned with Android)
    private var speechDetectionGainFactor: Float = 20.0 // Increased to match Android default gain for comparable levels
    private var adjustedSpeechThreshold: Float = defaultSpeechThreshold // Dynamic threshold based on background noise
    private var backgroundNoiseMultiplier: Float = 1.2 // Background noise calibration multiplier (aligned with Android)
    private var vadSpeechRatio: Float = 0.3 // Minimum ratio of speech frames in VAD window

    // VAD state tracking (now with configurable window size)
    private var recentEnergyLevels: [Float] = []
    private var energyIndex: Int = 0
    private var backgroundEnergyLevel: Float = 0.0
    private var backgroundCalibrated: Bool = false
    private var frameCount: Int = 0
    private var vadFrameCount: Int = 0 // Separate counter for simplified VAD

    // Non-VAD adaptive threshold tracking
    private var recentLevels: [Float] = Array(repeating: 0.0, count: adaptiveThresholdFrames)
    private var levelIndex: Int = 0
    private var adaptiveThreshold: Float = defaultSpeechThreshold
    private var adaptiveFrameCount: Int = 0

    // Background noise tracking for non-VAD speech detection (similar to Android)
    private var backgroundNoiseLevel: Float = 0.0
    private var backgroundNoiseCalibrated: Bool = false
    private var noiseCalibrationFrames: Int = 0
    private static let noiseCalibrationDuration = 30 // Frames for background noise calibration

    // Debug tracking
    private var debugFrameCount: Int = 0

    // Event callback
    private weak var eventCallback: WaveformEventCallback?

    // Thread safety
    private let queue = DispatchQueue(label: "waveform-data-queue", qos: .userInitiated)

    // MARK: - Protocols

    protocol WaveformEventCallback: AnyObject {
        func notifyListeners(_ eventName: String, data: [String: Any])
    }

    // MARK: - Initialization

    /**
     * Initialize WaveformDataManager with event callback
     * - Parameter eventCallback: Callback for emitting waveform data events
     */
    init(eventCallback: WaveformEventCallback?) {
        self.eventCallback = eventCallback

        // Initialize VAD energy levels array with default size
        self.recentEnergyLevels = Array(repeating: 0.0, count: vadWindowSize)

        log("WaveformDataManager initialized")
    }

    deinit {
        cleanup()
    }

    // MARK: - Public Methods

    /**
     * Configure the number of waveform bars
     * - Parameter bars: Number of bars in the waveform (default: 32)
     */
    func setNumberOfBars(_ bars: Int) {
        guard bars > 0 && bars <= 256 else { // Reasonable limits
            log("Invalid number of bars: \(bars), using default: \(Self.defaultBars)")
            numberOfBars = Self.defaultBars
            return
        }

        numberOfBars = bars
        log("Number of waveform bars set to: \(bars)")
    }

    /**
     * Configure waveform settings including debounce time
     * - Parameter debounceInSeconds: Debounce time in seconds (0.01 to 3600.0 seconds)
     * - Parameter bars: Number of bars in the waveform (1 to 256, optional, default: current value)
     */
    func configureWaveform(debounceInSeconds: Float, bars: Int = -1) {
        log("configureWaveform called with debounceInSeconds: \(debounceInSeconds), bars: \(bars)")
        log("Current debounceTime before update: \(debounceTime)")

        // Validate and set debounce time
        if debounceInSeconds >= 0.01 && debounceInSeconds <= 3600.0 {
            debounceTime = TimeInterval(debounceInSeconds)
            log("Debounce time set to: \(debounceInSeconds) seconds (\(debounceTime * 1000)ms)")
            log("Updated debounceTime: \(debounceTime)")
        } else {
            log("Invalid debounce time: \(debounceInSeconds) seconds, keeping current: \(debounceTime) seconds")
        }

        // Validate and set number of bars if provided
        if bars > 0 {
            setNumberOfBars(bars)
        }
    }

    /**
     * Configure waveform settings with debounce time only
     * - Parameter debounceInSeconds: Debounce time in seconds (0.01 to 3600.0 seconds)
     */
    func configureWaveform(debounceInSeconds: Float) {
        configureWaveform(debounceInSeconds: debounceInSeconds, bars: -1) // -1 means don't change bars
    }

    /**
     * Configure speech-only mode with threshold filtering
     * - Parameter enabled: Enable speech-only mode
     * - Parameter threshold: Amplitude threshold for speech detection (0.0-1.0)
     */
    func setSpeechOnlyMode(_ enabled: Bool, threshold: Float = defaultSpeechThreshold) {
        speechOnlyMode = enabled
        speechThreshold = max(0.0, min(1.0, threshold))
        log("Speech-only mode: \(enabled), threshold: \(speechThreshold)")

        if enabled {
            resetVadState()
        }
    }

    /**
     * Configure VAD window size for latency optimization
     * - Parameter windowSize: Number of frames to analyze (3-20 frames, default: 5)
     */
    func setVadWindowSize(_ windowSize: Int) {
        let newSize = max(3, min(Self.maxVadWindowSize, windowSize))
        if newSize != vadWindowSize {
            vadWindowSize = newSize
            // Reinitialize the energy levels array
            recentEnergyLevels = Array(repeating: 0.0, count: vadWindowSize)
            energyIndex = 0
            log("VAD window size set to: \(vadWindowSize) frames (~\(vadWindowSize * 50)ms latency)")

            // Only reset VAD state if currently enabled and not during initial configuration
            if vadEnabled && isActive {
                resetVadState()
            }
        }
    }

    /**
     * Enable or disable human voice band filtering for noise rejection
     * - Parameter enabled: Enable band-pass filtering for human voice frequencies (85Hz-3400Hz)
     */
    func setVoiceBandFilterEnabled(_ enabled: Bool) {
        voiceBandFilterEnabled = enabled
        log("Voice band filter: \(enabled ? "ENABLED" : "disabled") (filtering \(Self.minVoiceFreq)Hz-\(Self.maxVoiceFreq)Hz)")
    }

    /**
     * Enable or disable Voice Activity Detection (VAD)
     * - Parameter enabled: Enable VAD for more accurate speech detection
     */
    func setVadEnabled(_ enabled: Bool) {
        vadEnabled = enabled
        log("VAD enabled: \(enabled)")

        if enabled {
            resetVadState()
        }
    }

    /**
     * Set background noise calibration duration
     * - Parameter duration: Calibration duration in milliseconds
     */
    func setBackgroundCalibrationDuration(_ duration: Int) {
        backgroundCalibrationDuration = max(500, min(5000, duration)) // Limit between 500ms and 5000ms
        log("Background calibration duration set to: \(backgroundCalibrationDuration)ms")

        // Reset VAD state if currently enabled to apply new calibration duration
        if vadEnabled {
            resetVadState()
        }
    }

    /**
     * Configure speech detection with all parameters in one call
     * - Parameter enabled: Enable speech-only detection
     * - Parameter threshold: Amplitude threshold for speech detection (0.0-1.0)
     * - Parameter useVAD: Enable Voice Activity Detection for more accurate speech detection
     * - Parameter calibrationDuration: Background noise calibration duration in milliseconds
     */
    func configureSpeechDetection(enabled: Bool, threshold: Float, useVAD: Bool = false, calibrationDuration: Int = 1000) {
        speechOnlyMode = enabled
        speechThreshold = max(0.0, min(1.0, threshold))
        vadEnabled = useVAD
        backgroundCalibrationDuration = calibrationDuration

        log("Speech detection configured - enabled: \(enabled), threshold: \(speechThreshold), VAD: \(useVAD), calibration: \(calibrationDuration)ms, window: \(vadWindowSize) frames, voiceFilter: \(voiceBandFilterEnabled)")

        // Reset state for both VAD and non-VAD modes when speech detection is enabled
        if enabled {
            resetVadState()
        }
    }

    /**
     * Configure gain factor for better voice level representation
     * - Parameter gainFactor: Gain factor to apply to RMS values (5.0-30.0, default: 12.0)
     */
    func setGainFactor(_ gainFactor: Float) {
        speechDetectionGainFactor = max(5.0, min(50.0, gainFactor))
        log("Speech detection gain factor set to: \(speechDetectionGainFactor)")
    }

    /**
     * Configure advanced VAD settings for optimal performance
     * - Parameter enabled: Enable VAD
     * - Parameter windowSize: VAD window size in frames (3-20, smaller = lower latency)
     * - Parameter enableVoiceFilter: Enable human voice band filtering
     */
    func configureAdvancedVAD(enabled: Bool = false, windowSize: Int = 5, enableVoiceFilter: Bool = true) {
        log("Configuring Advanced VAD - enabled: \(enabled), windowSize: \(windowSize), voiceFilter: \(enableVoiceFilter)")

        // Update configuration without triggering excessive VAD resets
        setVadEnabled(enabled)
        setVoiceBandFilterEnabled(enableVoiceFilter)

        // Update window size last (this handles its own VAD reset if needed)
        setVadWindowSize(windowSize)

        log("Advanced VAD configured - enabled: \(enabled), window: \(vadWindowSize) frames (~\(vadWindowSize * 50)ms), voiceFilter: \(voiceBandFilterEnabled)")
    }

    /**
     * Configure waveform manager with recording parameters for optimal performance
     * - Parameter sampleRate: Recording sample rate (e.g., 48000)
     * - Parameter channels: Number of recording channels (e.g., 2 for stereo)
     * - Parameter speechThreshold: Speech detection threshold (0.0-1.0)
     */
    func configureForRecording(sampleRate: Int, channels: Int, speechThreshold: Float) {
        // Adjust speech threshold based on recording quality
        // Higher sample rates and stereo recording tend to have better SNR
        var adjustedThreshold = speechThreshold
        if sampleRate >= 48000 {
            // For high-quality recordings, we can use a slightly lower threshold
            adjustedThreshold = max(0.01, speechThreshold * 0.8)
        }

        self.speechThreshold = adjustedThreshold

        // Adjust gain factor based on sample rate and channel count to match Android behavior
        var optimalGain: Float = 20.0 // Base gain aligned with Android

        if sampleRate >= 48000 {
            optimalGain = 25.0 // Higher gain for 48kHz+ recording (match Android)
        }

        if channels >= 2 {
            optimalGain *= 1.15 // Slight gain boost for stereo recording
        }

        speechDetectionGainFactor = optimalGain

        log("Configured for recording - sampleRate: \(sampleRate), channels: \(channels), adjustedThreshold: \(adjustedThreshold), gainFactor: \(speechDetectionGainFactor)")

        // Reset calibration with new settings
        if speechOnlyMode {
            resetVadState()
        }
    }

    /**
     * Reset Voice Activity Detection and adaptive threshold state
     */
    private func resetVadState() {
        frameCount = 0
        vadFrameCount = 0
        energyIndex = 0
        backgroundEnergyLevel = 0.0
        backgroundCalibrated = false
        recentEnergyLevels = Array(repeating: 0.0, count: vadWindowSize) // Use configurable window size

        // Reset adaptive threshold state
        levelIndex = 0
        adaptiveThreshold = speechThreshold
        adaptiveFrameCount = 0
        recentLevels = Array(repeating: 0.0, count: Self.adaptiveThresholdFrames)

        // Reset background noise calibration (similar to Android)
        backgroundNoiseLevel = 0.0
        backgroundNoiseCalibrated = false
        noiseCalibrationFrames = 0

        // Reset adjusted threshold
        adjustedSpeechThreshold = speechThreshold

        // Reset debug counter
        debugFrameCount = 0
    }

    /**
     * Start waveform data monitoring
     * This should be called when recording starts
     */
    func startMonitoring() {
        queue.async { [weak self] in
            self?.startMonitoringInternal()
        }
    }

    /**
     * Stop waveform data monitoring
     */
    func stopMonitoring() {
        queue.async { [weak self] in
            self?.stopMonitoringInternal()
        }
    }

    /**
     * Pause waveform data monitoring
     */
    func pauseMonitoring() {
        queue.async { [weak self] in
            self?.isRecording = false
            self?.log("Waveform monitoring paused")
        }
    }

    /**
     * Resume waveform data monitoring
     */
    func resumeMonitoring() {
        queue.async { [weak self] in
            guard let self = self, self.isActive else { return }
            self.isRecording = true
            self.log("Waveform monitoring resumed")
        }
    }

    /**
     * Check if monitoring is currently active
     */
    func isMonitoring() -> Bool {
        return queue.sync {
            return isActive && isRecording
        }
    }

    // MARK: - Private Methods

    private func startMonitoringInternal() {
        guard !isActive else {
            log("Waveform monitoring is already active")
            return
        }

        do {
            // Create audio engine
            audioEngine = AVAudioEngine()
            guard let audioEngine = audioEngine else {
                log("Failed to create audio engine")
                return
            }

            // Get input node
            inputNode = audioEngine.inputNode
            guard let inputNode = inputNode else {
                log("Failed to get input node")
                return
            }

            // Use the input node's current format to avoid format mismatch
            let inputFormat = inputNode.outputFormat(forBus: 0)
            log("Using input format: \(inputFormat)")

            // Create a compatible format for our tap (mono conversion if needed)
            let tapFormat: AVAudioFormat
            if inputFormat.channelCount > 1 {
                // Convert to mono if input is stereo/multi-channel
                guard let monoFormat = AVAudioFormat(
                    commonFormat: .pcmFormatFloat32,
                    sampleRate: inputFormat.sampleRate,
                    channels: 1,
                    interleaved: false
                ) else {
                    log("Failed to create mono audio format")
                    return
                }
                tapFormat = monoFormat
            } else {
                // Use input format directly if it's already mono
                tapFormat = inputFormat
            }

            // Track current sample rate for voice-band detection calculations
            currentSampleRate = tapFormat.sampleRate

            log("Starting waveform monitoring with tap format: \(tapFormat)")

            // Install tap on input node to access PCM data
            inputNode.installTap(
                onBus: 0,
                bufferSize: Self.bufferSize,
                format: tapFormat
            ) { [weak self] (buffer, time) in
                self?.processAudioBuffer(buffer)
            }

            // Start audio engine
            try audioEngine.start()

            isActive = true
            isRecording = true
            lastEmissionTime = 0

            log("Waveform monitoring started with debounceTime: \(debounceTime) seconds (\(debounceTime * 1000)ms)")

            // Emit waveform init event
            emitWaveformInit()

            log("Waveform monitoring started successfully")

        } catch {
            log("Failed to start waveform monitoring: \(error.localizedDescription)")
            cleanup()
        }
    }

    private func stopMonitoringInternal() {
        guard isActive else {
            log("Waveform monitoring is not active")
            return
        }

        log("Stopping waveform monitoring")

        // Emit waveform destroy event before cleanup
        emitWaveformDestroy(reason: "stop_recording")

        isActive = false
        isRecording = false

        // Stop audio engine
        if let audioEngine = audioEngine {
            audioEngine.stop()
        }

        // Remove tap from input node
        if let inputNode = inputNode {
            inputNode.removeTap(onBus: 0)
        }

        // Clean up references
        audioEngine = nil
        inputNode = nil

        log("Waveform monitoring stopped")
    }

    /**
     * Process audio buffer and emit single waveform level with speech detection
     * - Parameter buffer: PCM audio buffer from AVAudioEngine
     */
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard isActive && isRecording else { return }

        // Check emission interval to maintain target frequency
        let currentTime = CACurrentMediaTime()
        let timeSinceLastEmission = currentTime - lastEmissionTime

        // Debug logging every 100 calls to avoid spam
        debugFrameCount += 1
        if debugFrameCount % 100 == 0 {
            log("processAudioBuffer: currentTime=\(currentTime), lastEmissionTime=\(lastEmissionTime), timeSinceLastEmission=\(timeSinceLastEmission), debounceTime=\(debounceTime), shouldEmit=\(timeSinceLastEmission >= debounceTime)")
        }

        guard timeSinceLastEmission >= debounceTime else { return }

        lastEmissionTime = currentTime

        // Get audio data - handle both mono and stereo
        guard let channelData = buffer.floatChannelData else { return }
        let frameLength = Int(buffer.frameLength)
        let channelCount = Int(buffer.format.channelCount)

        guard frameLength > 0 else { return }

        // Process audio data to create single waveform level
        let calculatedLevel = processAudioDataForSingleLevel(
            channelData: channelData,
            frameLength: frameLength,
            channelCount: channelCount
        )

        var emitLevel = calculatedLevel

        // Apply speech detection if enabled
        if speechOnlyMode {
            let isSpeech = detectSpeech(
                level: calculatedLevel,
                channelData: channelData[0], // Use first channel for speech detection
                frameLength: frameLength
            )
            if !isSpeech {
                emitLevel = 0.0 // Send level = 0 for silence instead of omitting
            }

            // Debug logging for speech detection (every 50 frames, similar to Android)
            if debugFrameCount % 50 == 0 {
                log("Speech detection: level=\(String(format: "%.3f", calculatedLevel)), threshold=\(String(format: "%.3f", speechThreshold)), isSpeech=\(isSpeech), emitLevel=\(String(format: "%.3f", emitLevel))")
            }
        } else {
            // Return raw levels without speech filtering
            emitLevel = calculatedLevel
            if debugFrameCount % 50 == 0 {
                log("Raw audio level: \(String(format: "%.3f", emitLevel)) (speech detection disabled)")
            }
        }

        // Always emit waveform level on main queue (continuous emission)
        DispatchQueue.main.async { [weak self] in
            self?.emitWaveformLevel(level: emitLevel)
        }
    }

    /**
     * Filter audio data to human voice frequency band (85Hz - 3400Hz)
     * Uses optimized filtering for higher sample rates
     */
    private func applyVoiceBandFilter(samples: UnsafePointer<Float>, frameLength: Int) -> [Float] {
        // If filter disabled, return raw samples
        guard voiceBandFilterEnabled else {
            return Array(UnsafeBufferPointer(start: samples, count: frameLength))
        }

        // Compute zero crossing rate for the current buffer
        let zeroCrossings = calculateZeroCrossingRate(channelData: samples, frameLength: frameLength)

        // Calculate expected zero-crossing range for human voice based on current sample rate
        let expectedMinZCR = (Self.minVoiceFreq * 2.0 * Float(frameLength)) / Float(currentSampleRate)
        let expectedMaxZCR = (Self.maxVoiceFreq * 2.0 * Float(frameLength)) / Float(currentSampleRate)

        let inVoiceBand = Float(zeroCrossings) >= expectedMinZCR && Float(zeroCrossings) <= expectedMaxZCR

        // If out of band, attenuate samples (similar to Android's level attenuation)
        if !inVoiceBand {
            var attenuated = [Float]()
            attenuated.reserveCapacity(frameLength)
            for i in 0..<frameLength {
                attenuated.append(samples[i] * 0.3)
            }
            return attenuated
        }

        // In-band: keep original samples to preserve amplitude
        return Array(UnsafeBufferPointer(start: samples, count: frameLength))
    }

    /**
     * Process raw audio data into a single normalized level using RMS with automatic gain
     * - Parameters:
     *   - channelData: Pointer to float audio samples (supports multiple channels)
     *   - frameLength: Number of audio frames
     *   - channelCount: Number of audio channels
     * - Returns: Single normalized amplitude level (0-1) with gain applied if needed
     */
    private func processAudioDataForSingleLevel(channelData: UnsafePointer<UnsafeMutablePointer<Float>>, frameLength: Int, channelCount: Int) -> Float {
        var sum: Float = 0.0
        var totalSamples = 0

        // Process all channels and average them
        for channel in 0..<channelCount {
            let samples = channelData[channel]

            // Apply voice band filtering if enabled
            let processedSamples = applyVoiceBandFilter(samples: samples, frameLength: frameLength)

            for sample in processedSamples {
                sum += sample * sample
                totalSamples += 1
            }
        }

        guard totalSamples > 0 else { return 0.0 }

        // Calculate RMS with updated gain factor
        let rms = sqrt(sum / Float(totalSamples))
        let adjustedRms = rms * speechDetectionGainFactor

        // Ensure it's in 0-1 range
        return min(1.0, max(0.0, adjustedRms))
    }

    /**
     * Detect speech in the audio buffer using adaptive threshold and optional VAD
     * - Parameter level: Normalized RMS level
     * - Parameter channelData: Pointer to float audio samples
     * - Parameter frameLength: Number of audio frames
     * - Returns: true if speech is detected
     */
    private func detectSpeech(level: Float, channelData: UnsafePointer<Float>, frameLength: Int) -> Bool {
        guard vadEnabled else {
            // Use background noise calibration similar to Android for non-VAD mode
            return performBackgroundNoiseDetection(level: level)
        }

        // Enhanced VAD (Voice Activity Detection) with static threshold
        let aboveThreshold = level > speechThreshold
        return performVAD(currentLevel: level, channelData: channelData, frameLength: frameLength) && aboveThreshold
    }

    /**
     * Perform background noise calibration and speech detection for non-VAD mode
     * - Parameter level: Current RMS level
     * - Returns: true if speech is detected
     */
    private func performBackgroundNoiseDetection(level: Float) -> Bool {
        // Calibrate background noise level for the first few frames (similar to Android)
        if !backgroundNoiseCalibrated && noiseCalibrationFrames < Self.noiseCalibrationDuration {
            backgroundNoiseLevel += level
            noiseCalibrationFrames += 1

            if noiseCalibrationFrames == Self.noiseCalibrationDuration {
                backgroundNoiseLevel = backgroundNoiseLevel / Float(Self.noiseCalibrationDuration)
                // Add a safety margin to the background noise level (aligned with Android)
                backgroundNoiseLevel = backgroundNoiseLevel * 1.2
                backgroundNoiseCalibrated = true
                log("Background noise calibrated: \(backgroundNoiseLevel)")
            }
            return false // Don't detect speech during calibration
        }

        // Use adaptive threshold based on background noise if calibrated
        var effectiveThreshold = speechThreshold
        if backgroundNoiseCalibrated {
            // Use the higher of configured threshold or background noise + smaller margin (aligned with Android)
            effectiveThreshold = max(speechThreshold, backgroundNoiseLevel + 0.005)
        }

        let isSpeech = level > effectiveThreshold

        // Debug logging for speech detection (every 50 frames)
        if noiseCalibrationFrames % 50 == 0 && backgroundNoiseCalibrated {
            log("Speech detection: level=\(String(format: "%.3f", level)), threshold=\(String(format: "%.3f", effectiveThreshold)), backgroundNoise=\(String(format: "%.3f", backgroundNoiseLevel)), isSpeech=\(isSpeech)")
        }

        return isSpeech
    }

    /**
     * Perform adaptive threshold speech detection for non-VAD mode
     * - Parameter level: Current RMS level
     * - Returns: true if speech is detected
     */
    private func performAdaptiveThresholdDetection(level: Float) -> Bool {
        adaptiveFrameCount += 1

        // Update recent levels history
        recentLevels[levelIndex] = level
        levelIndex = (levelIndex + 1) % Self.adaptiveThresholdFrames

        // Calculate adaptive threshold after collecting enough samples
        if adaptiveFrameCount >= Self.adaptiveThresholdFrames {
            let sortedLevels = recentLevels.sorted()

            // Use percentile-based threshold (bottom 75% average + margin)
            let percentileIndex = Int(Float(Self.adaptiveThresholdFrames) * 0.75)
            let backgroundLevel = Array(sortedLevels[0..<percentileIndex]).reduce(0, +) / Float(percentileIndex)

            // Set adaptive threshold as background + margin, within min/max bounds
            let proposedThreshold = backgroundLevel + (backgroundLevel * 0.8) // 80% increase over background
            adaptiveThreshold = max(Self.minSpeechThreshold, min(Self.maxSpeechThreshold, proposedThreshold))

            // Also use user-configured threshold as a baseline
            let finalThreshold = max(speechThreshold, adaptiveThreshold)

            if adaptiveFrameCount % 50 == 0 { // Log every 50 frames for debugging
                log("Adaptive threshold: bg=\(String(format: "%.4f", backgroundLevel)), adaptive=\(String(format: "%.4f", adaptiveThreshold)), final=\(String(format: "%.4f", finalThreshold)), current=\(String(format: "%.4f", level))")
            }

            return level > finalThreshold
        } else {
            // Use static threshold during calibration
            return level > speechThreshold
        }
    }

    /**
     * Perform Voice Activity Detection using energy and spectral analysis
     * - Parameter currentLevel: Current RMS level
     * - Parameter channelData: Pointer to float audio samples
     * - Parameter frameLength: Number of audio frames
     * - Returns: true if voice activity is detected
     */
    private func performVAD(currentLevel: Float, channelData: UnsafePointer<Float>, frameLength: Int) -> Bool {
        frameCount += 1

        // Update energy level history
        recentEnergyLevels[energyIndex] = currentLevel
        energyIndex = (energyIndex + 1) % vadWindowSize

        // Calculate calibration frames based on configurable duration
        // At current emission interval, calculate how many frames needed for calibration duration
        let intervalMs = Int(debounceTime * 1000)
        let calibrationFrames = max(2, backgroundCalibrationDuration / intervalMs) // Minimum 2 frames

        // Calibrate background noise level using configurable duration
        if !backgroundCalibrated && frameCount <= calibrationFrames {
            backgroundEnergyLevel += currentLevel
            if frameCount == calibrationFrames {
                backgroundEnergyLevel = (backgroundEnergyLevel / Float(calibrationFrames)) * Self.energyThresholdMultiplier
                backgroundCalibrated = true
                log("VAD background energy calibrated after \(calibrationFrames) frames (\(backgroundCalibrationDuration)ms): \(backgroundEnergyLevel)")
            }
            return false // Don't emit during calibration
        }

        guard backgroundCalibrated else {
            return false
        }

        // Energy-based detection
        let energyCheck = currentLevel > backgroundEnergyLevel

        // Calculate zero crossing rate for additional speech characteristics
        let zeroCrossings = calculateZeroCrossingRate(channelData: channelData, frameLength: frameLength)
        let zcrCheck = zeroCrossings > 10 && zeroCrossings < 1000 // Typical speech range

        // Count recent frames above energy threshold
        let speechFrames = recentEnergyLevels.filter { $0 > backgroundEnergyLevel }.count
        let speechRatio = Float(speechFrames) / Float(vadWindowSize)
        let continuityCheck = speechRatio >= vadSpeechRatio

        // Combined VAD decision
        let isSpeech = energyCheck && zcrCheck && continuityCheck

        if frameCount % 100 == 0 { // Log every 100 frames for debugging
            log("VAD: energy=\(String(format: "%.3f", currentLevel)) (bg=\(String(format: "%.3f", backgroundEnergyLevel))), zcr=\(zeroCrossings), ratio=\(String(format: "%.2f", speechRatio)), speech=\(isSpeech)")
        }

        return isSpeech
    }

    /**
     * Simplified VAD that works with RMS level only (matching Android implementation)
     * - Parameter rmsLevel: Current RMS energy level
     * - Returns: Speech level (0.0 if no speech detected, rmsLevel if speech detected)
     */
    private func performVAD(rmsLevel: Float) -> Float {
        vadFrameCount += 1

        // Update energy level history
        recentEnergyLevels[energyIndex] = rmsLevel
        energyIndex = (energyIndex + 1) % vadWindowSize

        // Calculate calibration frames based on configurable duration
        let intervalMs = Int(debounceTime * 1000)
        let calibrationFrames = max(2, backgroundCalibrationDuration / intervalMs) // Minimum 2 frames

        // Calibrate background noise level using configurable duration
        if !backgroundCalibrated && vadFrameCount <= calibrationFrames {
            backgroundEnergyLevel += rmsLevel
            if vadFrameCount == calibrationFrames {
                backgroundEnergyLevel = (backgroundEnergyLevel / Float(calibrationFrames)) * backgroundNoiseMultiplier
                backgroundCalibrated = true
                adjustedSpeechThreshold = max(speechThreshold, backgroundEnergyLevel)
                log("VAD background energy calibrated after \(calibrationFrames) frames (\(backgroundCalibrationDuration)ms): \(backgroundEnergyLevel), adjusted threshold: \(adjustedSpeechThreshold)")
            }
            return 0.0 // Don't emit during calibration
        }

        guard backgroundCalibrated else {
            return 0.0
        }

        // Energy-based detection
        let energyCheck = rmsLevel > adjustedSpeechThreshold

        // Count recent frames above energy threshold
        let speechFrames = recentEnergyLevels.filter { $0 > adjustedSpeechThreshold }.count
        let speechRatio = Float(speechFrames) / Float(vadWindowSize)
        let continuityCheck = speechRatio >= vadSpeechRatio

        // Combined VAD decision
        let isSpeech = energyCheck && continuityCheck

        return isSpeech ? rmsLevel : 0.0
    }

    /**
     * Calculate zero crossing rate for speech detection
     * - Parameter channelData: Pointer to float audio samples
     * - Parameter frameLength: Number of audio frames
     * - Returns: Zero crossing rate
     */
    private func calculateZeroCrossingRate(channelData: UnsafePointer<Float>, frameLength: Int) -> Int {
        var crossings = 0
        for i in 1..<frameLength {
            if (channelData[i] >= 0) != (channelData[i-1] >= 0) {
                crossings += 1
            }
        }
        return crossings
    }

    /**
     * Emit single waveform level to JavaScript for growing waveform
     * - Parameter level: Normalized amplitude level (0-1)
     */
    private func emitWaveformLevel(level: Float) {
        guard let eventCallback = eventCallback else {
            log("No event callback available for waveform level")
            return
        }

        let data: [String: Any] = [
            "level": level
        ]

        eventCallback.notifyListeners("waveformData", data: data)
    }

    /**
     * Emit waveform initialization event
     */
    private func emitWaveformInit() {
        guard let eventCallback = eventCallback else {
            log("No event callback available for waveform init")
            return
        }

        let data: [String: Any] = [
            "numberOfBars": numberOfBars,
            "speechOnlyMode": speechOnlyMode,
            "speechThreshold": speechThreshold,
            "vadEnabled": vadEnabled,
            "calibrationDuration": backgroundCalibrationDuration
        ]

        eventCallback.notifyListeners("waveformInit", data: data)
    }

    /**
     * Emit waveform destroy event
     * - Parameter reason: Reason for destruction
     */
    private func emitWaveformDestroy(reason: String) {
        guard let eventCallback = eventCallback else {
            log("No event callback available for waveform destroy")
            return
        }

        let data: [String: Any] = [
            "reason": reason,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000) // milliseconds
        ]

        eventCallback.notifyListeners("waveformDestroy", data: data)
    }

    /**
     * Clean up resources
     */
    func cleanup() {
        queue.async { [weak self] in
            self?.stopMonitoringInternal()
        }
    }

    // MARK: - Utility Methods

    private func log(_ message: String) {
        #if DEBUG
        print("[WaveformDataManager] \(message)")
        #endif
    }
}
