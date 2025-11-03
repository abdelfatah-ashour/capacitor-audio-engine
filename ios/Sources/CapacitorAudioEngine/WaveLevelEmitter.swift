import Foundation
import AVFoundation

// MARK: - Protocols
protocol WaveLevelEventCallback: AnyObject {
    func notifyListeners(_ eventName: String, data: [String: Any])
}

/**
 * WaveLevelEmitter handles real-time audio level monitoring during recording on iOS
 * Simplified implementation focused on emitting normalized wave levels (0.0-1.0)
 * at configurable intervals for UI visualization and speaker activity detection.
 *
 * Features:
 * - Real-time RMS-based audio level calculation using AVAudioEngine
 * - Configurable emission frequency (50-500ms, default 1000ms)
 * - Normalized amplitude values between 0.0 and 1.0
 * - Optimized for human speech detection (-50dB to -10dB range)
 * - Gentle compression curve for better visual representation
 * - Cross-platform consistency with Android implementation
 * - Low CPU overhead with efficient audio tap processing
 * - Automatic hardware format adaptation (supports 44.1kHz, 48kHz, etc.)
 * - Multi-channel audio support with automatic mono conversion
 */
class WaveLevelEmitter {

    // MARK: - Configuration Constants

    private static let defaultEmissionIntervalMs = 1000 // Default 1000ms as per SRS
    private static let minEmissionIntervalMs = 50 // Minimum 50ms as per SRS
    private static let maxEmissionIntervalMs = 500 // Maximum 500ms as per SRS
    private static let defaultSampleRate: Double = 44100.0
    private static let bufferSize: AVAudioFrameCount = 1024

    // Normalization constants
    private static let silenceThreshold: Float = 0.001 // Very small threshold for silence detection

    // MARK: - Properties

    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?

    // Configuration
    private var emissionIntervalMs: Int = defaultEmissionIntervalMs
    private var emissionInterval: TimeInterval { return TimeInterval(emissionIntervalMs) / 1000.0 }

    // Monitoring state
    private var isMonitoringActive: Bool = false
    private var isMonitoringPaused: Bool = false
    private var lastEmissionTime: TimeInterval = 0

    // Callback dispatch queue
    private var callbackQueue: DispatchQueue = .main

    // Event callback
    private weak var eventCallback: WaveLevelEventCallback?

    // Thread safety
    private let queue = DispatchQueue(label: "wave-level-queue", qos: .userInitiated)

    // MARK: - Initialization

    /**
     * Initialize WaveLevelEmitter with event callback
     * - Parameter eventCallback: Callback for emitting wave level events
     */
    init(eventCallback: WaveLevelEventCallback?) {
        self.eventCallback = eventCallback
        log("WaveLevelEmitter initialized")
    }

    deinit {
        cleanup()
    }

    // MARK: - Public Methods

    /**
     * Configure emission interval (frequency of wave level updates)
     * - Parameter intervalMs: Emission interval in milliseconds (50-500ms, default: 1000ms)
     */
    func setEmissionInterval(_ intervalMs: Int) {
        if intervalMs >= Self.minEmissionIntervalMs && intervalMs <= Self.maxEmissionIntervalMs {
            emissionIntervalMs = intervalMs
            log("Emission interval set to: \(intervalMs)ms")
        } else {
            log("Invalid emission interval: \(intervalMs)ms. Must be between \(Self.minEmissionIntervalMs)-\(Self.maxEmissionIntervalMs)ms")
        }
    }

    /**
     * Start wave level monitoring
     * This should be called when recording starts
     */
    func startMonitoring() {
        queue.async { [weak self] in
            self?.startMonitoringInternal()
        }
    }

    /**
     * Stop wave level monitoring
     */
    func stopMonitoring() {
        queue.async { [weak self] in
            self?.stopMonitoringInternal()
        }
    }

    /**
     * Pause wave level monitoring
     */
    func pauseMonitoring() {
        queue.async { [weak self] in
            guard let self = self else { return }
            isMonitoringPaused = true
            log("Wave level monitoring paused")
        }
    }

    /**
     * Resume wave level monitoring
     */
    func resumeMonitoring() {
        queue.async { [weak self] in
            guard let self = self else { return }
            if isMonitoringActive {
                isMonitoringPaused = false
                log("Wave level monitoring resumed")
            }
        }
    }

    /**
     * Check if monitoring is currently active
     */
    func isMonitoring() -> Bool {
        return queue.sync {
            return isMonitoringActive && !isMonitoringPaused
        }
    }

    // MARK: - Private Methods

    private func startMonitoringInternal() {
        guard !isMonitoringActive else {
            log("Monitoring already active")
            return
        }

        log("Starting wave level monitoring with interval: \(emissionIntervalMs)ms")

        do {
            // Initialize audio engine
            audioEngine = AVAudioEngine()
            guard let audioEngine = audioEngine else {
                emitError("Failed to create audio engine")
                return
            }

            inputNode = audioEngine.inputNode
            guard let inputNode = inputNode else {
                emitError("Failed to get input node")
                return
            }

            // Get the input format
            let inputFormat = inputNode.outputFormat(forBus: 0)
            log("Input format: \(inputFormat.sampleRate)Hz, \(inputFormat.channelCount) channels")

            // Configure audio session for recording
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try audioSession.setActive(true)

            // Install tap on input node for real-time audio processing
            inputNode.installTap(onBus: 0, bufferSize: Self.bufferSize, format: inputFormat) { [weak self] (buffer, time) in
                self?.processAudioBuffer(buffer)
            }

            // Start the audio engine
            try audioEngine.start()

            isMonitoringActive = true
            isMonitoringPaused = false
            lastEmissionTime = 0

            emitInit()
            log("Wave level monitoring started")

        } catch {
            emitError("Failed to start audio monitoring: \(error.localizedDescription)")
            cleanup()
        }
    }

    private func stopMonitoringInternal() {
        guard isMonitoringActive else {
            log("Monitoring not active")
            return
        }

        log("Stopping wave level monitoring")

        // Emit destroy event before cleanup
        emitDestroy(reason: "stop_recording")

        isMonitoringActive = false
        isMonitoringPaused = false

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

        log("Wave level monitoring stopped")
    }

    /**
     * Process audio buffer and emit wave level at configured intervals
     * - Parameter buffer: PCM audio buffer from AVAudioEngine
     */
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard isMonitoringActive && !isMonitoringPaused else { return }

        // Check if it's time to emit a level
        let currentTime = CACurrentMediaTime()
        if lastEmissionTime != 0 && (currentTime - lastEmissionTime) < emissionInterval {
            return
        }

        lastEmissionTime = currentTime

        // Get audio data - handle both mono and stereo
        guard let channelData = buffer.floatChannelData else { return }
        let frameLength = Int(buffer.frameLength)
        let channelCount = Int(buffer.format.channelCount)

        guard frameLength > 0 else { return }

        // Calculate RMS level with multi-channel support
        let level = calculateRMSLevel(channelData: channelData, frameLength: frameLength, channelCount: channelCount)

        // Apply silence threshold
        let finalLevel = level < Self.silenceThreshold ? 0.0 : level

        // Emit the level on callback queue
        let timestamp = Int64(currentTime * 1000) // Convert to milliseconds
        callbackQueue.async { [weak self] in
            self?.emitWaveLevel(level: finalLevel, timestamp: timestamp)
        }
    }

    /**
     * Calculate RMS level from audio data
     * - Parameters:
     *   - channelData: Pointer to float audio samples (supports multiple channels)
     *   - frameLength: Number of audio frames
     *   - channelCount: Number of audio channels
     * - Returns: Normalized RMS level (0.0-1.0)
     */
    private func calculateRMSLevel(channelData: UnsafePointer<UnsafeMutablePointer<Float>>, frameLength: Int, channelCount: Int) -> Float {
        var channelRMS: [Float] = []
        channelRMS.reserveCapacity(channelCount)

        // Calculate RMS for each channel
        for channel in 0..<channelCount {
            let samples = channelData[channel]
            var sum: Float = 0.0

            for frame in 0..<frameLength {
                let sample = samples[frame]
                sum += sample * sample
            }

            let rms = sqrt(sum / Float(frameLength))
            channelRMS.append(rms)
        }

        // Combine channels (use average for consistent behavior across platforms)
        let combinedRMS = channelRMS.reduce(0, +) / Float(channelRMS.count)

        // Convert RMS to dB - optimized for human speech
        // Human speech typically ranges from -40dB to -10dB
        // Using -50dB to -10dB provides better sensitivity for normal conversation
        let minDb: Float = -50.0 // Adjusted for human speech (was -60dB)
        let maxDb: Float = -10.0 // Adjusted for human speech (was 0dB)
        let rmsDb = 20.0 * log10(combinedRMS + 1e-8) // Avoid log(0)

        // Normalize dB to 0.0–1.0 (minDb maps to 0, maxDb to 1)
        var normalized = (rmsDb - minDb) / (maxDb - minDb)
        normalized = max(0.0, min(1.0, normalized))

        // Apply gentle compression curve for better visual representation
        // This makes lower levels more visible while preserving dynamic range
        normalized = pow(normalized, 0.7)

        return normalized
    }

    /**
     * Emit wave level event to JavaScript
     * - Parameter level: Normalized amplitude level (0.0-1.0)
     * - Parameter timestamp: Timestamp in milliseconds
     */
    private func emitWaveLevel(level: Float, timestamp: Int64) {
        let data: [String: Any] = [
            "level": level,
            "timestamp": timestamp
        ]
        eventCallback?.notifyListeners("waveLevel", data: data)
    }

    /**
     * Emit initialization event
     */
    private func emitInit() {
        let data: [String: Any] = [
            "status": "initialized",
            "emissionInterval": emissionIntervalMs,
            "sampleRate": audioEngine?.inputNode.outputFormat(forBus: 0).sampleRate ?? Self.defaultSampleRate
        ]
        callbackQueue.async { [weak self] in
            self?.eventCallback?.notifyListeners("waveLevelInit", data: data)
        }
        log("Wave level monitoring initialized")
    }

    /**
     * Emit destroy event
     * - Parameter reason: Reason for destruction
     */
    private func emitDestroy(reason: String) {
        let data: [String: Any] = [
            "status": "destroyed",
            "reason": reason
        ]
        callbackQueue.async { [weak self] in
            self?.eventCallback?.notifyListeners("waveLevelDestroy", data: data)
        }
        log("Wave level monitoring destroyed: \(reason)")
    }

    /**
     * Emit error event
     * - Parameter message: Error message
     */
    private func emitError(_ message: String) {
        let data: [String: Any] = [
            "error": message
        ]
        callbackQueue.async { [weak self] in
            self?.eventCallback?.notifyListeners("waveLevelError", data: data)
        }
        log("Wave level error: \(message)")
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
        print("[WaveLevelEmitter] \(message)")
    }
}
