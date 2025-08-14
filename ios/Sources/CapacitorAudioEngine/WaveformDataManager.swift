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

    private static let defaultBars = 32
    private static let emissionIntervalMs: TimeInterval = 0.05 // 20fps for continuous emission (50ms as requested)
    private static let sampleRate: Double = 44100.0
    private static let bufferSize: AVAudioFrameCount = 1024

    // Speech detection constants
    private static let defaultSpeechThreshold: Float = 0.005 // Lowered threshold for better iOS sensitivity
    private static let vadWindowSize = 10 // Number of frames to analyze for VAD
    private static let vadSpeechRatio: Float = 0.3 // Minimum ratio of speech frames in window
    private static let energyThresholdMultiplier: Float = 2.0 // Energy threshold multiplier for VAD

    // Non-VAD speech detection constants
    private static let adaptiveThresholdFrames = 20 // Frames to calculate adaptive threshold
    private static let minSpeechThreshold: Float = 0.002 // Minimum threshold for very quiet environments
    private static let maxSpeechThreshold: Float = 0.05 // Maximum threshold to prevent false positives

    // MARK: - Properties

    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var numberOfBars: Int = defaultBars
    private var emissionIntervalMs: TimeInterval = 0.05 // Configurable emission interval, initialized with default value
    private var isActive: Bool = false
    private var isRecording: Bool = false
    private var lastEmissionTime: TimeInterval = 0

    // Speech detection properties
    private var speechOnlyMode: Bool = false
    private var speechThreshold: Float = defaultSpeechThreshold
    private var vadEnabled: Bool = false
    private var backgroundCalibrationDuration: Int = 1000 // Default 1 second (matching Android)

    // VAD state tracking
    private var recentEnergyLevels: [Float] = Array(repeating: 0.0, count: vadWindowSize)
    private var energyIndex: Int = 0
    private var backgroundEnergyLevel: Float = 0.0
    private var backgroundCalibrated: Bool = false
    private var frameCount: Int = 0

    // Non-VAD adaptive threshold tracking
    private var recentLevels: [Float] = Array(repeating: 0.0, count: adaptiveThresholdFrames)
    private var levelIndex: Int = 0
    private var adaptiveThreshold: Float = defaultSpeechThreshold
    private var adaptiveFrameCount: Int = 0

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
     * Configure waveform settings including emission interval
     * - Parameter debounceInSeconds: Emission interval in seconds (0.01 to 3600.0 seconds)
     * - Parameter bars: Number of bars in the waveform (1 to 256, optional, default: current value)
     */
    func configureWaveform(debounceInSeconds: Float, bars: Int = -1) {
        log("configureWaveform called with debounceInSeconds: \(debounceInSeconds), bars: \(bars)")
        log("Current emissionIntervalMs before update: \(emissionIntervalMs)")

        // Validate and set emission interval
        if debounceInSeconds >= 0.01 && debounceInSeconds <= 3600.0 {
            emissionIntervalMs = TimeInterval(debounceInSeconds)
            log("Emission interval set to: \(debounceInSeconds) seconds (\(emissionIntervalMs * 1000)ms)")
            log("Updated emissionIntervalMs: \(emissionIntervalMs)")
        } else {
            log("Invalid emission interval: \(debounceInSeconds) seconds, keeping current: \(emissionIntervalMs) seconds")
        }

        // Validate and set number of bars if provided
        if bars > 0 {
            setNumberOfBars(bars)
        }
    }

    /**
     * Configure waveform settings with emission interval only
     * - Parameter debounceInSeconds: Emission interval in seconds (0.01 to 3600.0 seconds)
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
    func configureSpeechDetection(enabled: Bool, threshold: Float, useVAD: Bool, calibrationDuration: Int) {
        speechOnlyMode = enabled
        speechThreshold = max(0.0, min(1.0, threshold))
        vadEnabled = useVAD
        backgroundCalibrationDuration = calibrationDuration

        log("Speech detection configured - enabled: \(enabled), threshold: \(speechThreshold), VAD: \(useVAD), calibration: \(calibrationDuration)ms")

        // Reset state for both VAD and non-VAD modes when speech detection is enabled
        if enabled {
            resetVadState()
        }
    }

    /**
     * Reset Voice Activity Detection and adaptive threshold state
     */
    private func resetVadState() {
        frameCount = 0
        energyIndex = 0
        backgroundEnergyLevel = 0.0
        backgroundCalibrated = false
        recentEnergyLevels = Array(repeating: 0.0, count: Self.vadWindowSize)

        // Reset adaptive threshold state
        levelIndex = 0
        adaptiveThreshold = speechThreshold
        adaptiveFrameCount = 0
        recentLevels = Array(repeating: 0.0, count: Self.adaptiveThresholdFrames)

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

            log("Waveform monitoring started with emissionIntervalMs: \(emissionIntervalMs) seconds (\(emissionIntervalMs * 1000)ms)")

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
            log("processAudioBuffer: currentTime=\(currentTime), lastEmissionTime=\(lastEmissionTime), timeSinceLastEmission=\(timeSinceLastEmission), emissionIntervalMs=\(emissionIntervalMs), shouldEmit=\(timeSinceLastEmission >= emissionIntervalMs)")
        }

        guard timeSinceLastEmission >= emissionIntervalMs else { return }

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

            // Debug logging for speech detection (every 25 frames)
            if debugFrameCount % 25 == 0 {
                log("Audio level: \(String(format: "%.4f", calculatedLevel)), speech: \(isSpeech), threshold: \(String(format: "%.4f", speechThreshold)), VAD: \(vadEnabled)")
            }
        }

        // Always emit waveform level on main queue (continuous emission)
        DispatchQueue.main.async { [weak self] in
            self?.emitWaveformLevel(level: emitLevel)
        }
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
            for i in 0..<frameLength {
                let sample = samples[i]
                sum += sample * sample
                totalSamples += 1
            }
        }

        guard totalSamples > 0 else { return 0.0 }

        // Calculate RMS
        let rms = sqrt(sum / Float(totalSamples))

        // Apply automatic gain for iOS audio to match recorded audio levels
        // iOS AVAudioRecorder applies AGC and normalization, so we boost raw levels to compensate
        let gainFactor: Float = 8.0 // Increased from 4.0 to better match final recording levels
        let adjustedRms = rms * gainFactor

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
            // Use adaptive threshold for better speech detection without VAD
            return performAdaptiveThresholdDetection(level: level)
        }

        // Enhanced VAD (Voice Activity Detection) with static threshold
        let aboveThreshold = level > speechThreshold
        return performVAD(currentLevel: level, channelData: channelData, frameLength: frameLength) && aboveThreshold
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
        energyIndex = (energyIndex + 1) % Self.vadWindowSize

        // Calculate calibration frames based on configurable duration
        // At current emission interval, calculate how many frames needed for calibration duration
        let intervalMs = Int(emissionIntervalMs * 1000)
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
        let speechRatio = Float(speechFrames) / Float(Self.vadWindowSize)
        let continuityCheck = speechRatio >= Self.vadSpeechRatio

        // Combined VAD decision
        let isSpeech = energyCheck && zcrCheck && continuityCheck

        if frameCount % 100 == 0 { // Log every 100 frames for debugging
            log("VAD: energy=\(String(format: "%.3f", currentLevel)) (bg=\(String(format: "%.3f", backgroundEnergyLevel))), zcr=\(zeroCrossings), ratio=\(String(format: "%.2f", speechRatio)), speech=\(isSpeech)")
        }

        return isSpeech
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
