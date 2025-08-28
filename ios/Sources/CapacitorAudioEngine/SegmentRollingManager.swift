import Foundation
import UIKit
@preconcurrency import AVFoundation

/**
 * Manages segment rolling audio recording with automatic cleanup
 * Features:
 * - Records audio in 5-minute segments for all recordings (improved performance)
 * - When no maxDuration is set, maintains all segments (unlimited recording)
 * - When maxDuration is set, maintains a rolling buffer (keeps only last N seconds)
 * - Duration reporting continues to count up (never resets)
 * - Final recording contains only the buffered segments of audio
 * - Automatically removes oldest segments when buffer reaches capacity
 * - Merges segments into final audio file when recording stops
 */
class SegmentRollingManager: NSObject {

    // MARK: - Properties

    private var segmentBuffer: [URL] = []
    private var segmentTimer: Timer?
    private var currentSegmentRecorder: AVAudioRecorder?
    // Continuous full-session recorder for 0s merge at stop
    private var continuousRecorder: AVAudioRecorder?
    private var continuousOutputURL: URL?
    private var isContinuousActive: Bool = false
    private var recordingSession: AVAudioSession?
    private var segmentCounter: Int = 0
    private var isActive: Bool = false
    private let segmentsDirectory: URL

    // Cleanup idempotency flag
    private var hasCleanedUp: Bool = false

    // Recording settings
    private var recordingSettings: [String: Any] = [:]

    // Duration control
    private var maxDuration: TimeInterval?

    // Total recording duration tracking (never resets)
    private var totalRecordingDuration: TimeInterval = 0
    private var recordingStartTime: Date?

    // Pause/Resume tracking
    private var pausedTime: Date?
    private var totalPausedDuration: TimeInterval = 0
    private var isPaused: Bool = false

    // Thread safety
    private let queue = DispatchQueue(label: "segment-rolling-queue", qos: .userInteractive)
    private let queueKey = DispatchSpecificKey<String>()
    private let queueValue = "segment-rolling-queue"

    // MARK: - Initialization

    // Throttled logging state
    private static var lastLogTimes: [String: Date] = [:]

    // MARK: - Custom Error Types

    enum SegmentRecordingError: Error, LocalizedError {
        case alreadyActive
        case notActive
        case noRecordingToResume
        case noRecordingToStop
        case noValidSegments
        case recordingTooShort
        case noRecordingFile
        case recorderCreationFailed(Error)
        case recordingStartFailed
        case audioTrackCreationFailed
        case exportSessionCreationFailed
        case exportFailed(String)
        case trimExportFailed(String)
        case segmentRotationFailed(Error)
        case cleanupFailed(Error)

        var errorDescription: String? {
            switch self {
            case .alreadyActive:
                return "Segment rolling already active"
            case .notActive:
                return "No segment rolling session active"
            case .noRecordingToResume:
                return "No segment rolling to resume"
            case .noRecordingToStop:
                return "No segment rolling to stop"
            case .noValidSegments:
                return "No valid recording segments found - recording may have been too short"
            case .recordingTooShort:
                return "Recording too short to process"
            case .noRecordingFile:
                return "No recording file found"
            case .recorderCreationFailed(let error):
                return "Failed to create audio recorder: \(error.localizedDescription)"
            case .recordingStartFailed:
                return "Failed to start segment recording"
            case .audioTrackCreationFailed:
                return "Failed to create audio track for composition"
            case .exportSessionCreationFailed:
                return "Failed to create export session"
            case .exportFailed(let status):
                return "Export failed with status: \(status)"
            case .trimExportFailed(let status):
                return "Audio trimming failed with status: \(status)"
            case .segmentRotationFailed(let error):
                return "Failed to rotate segment: \(error.localizedDescription)"
            case .cleanupFailed(let error):
                return "Cleanup failed: \(error.localizedDescription)"
            }
        }
    }

    // MARK: - Queue Management Helper

    /// Centralized queue check helper to prevent deadlocks and reduce boilerplate
    private func performQueueSafeOperation<T>(_ operation: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: queueKey) == queueValue {
            return try operation()
        } else {
            return try queue.sync { try operation() }
        }
    }

    /// Async version of queue safe operation for async operations
    private func performQueueSafeOperationAsync<T>(_ operation: @escaping () async throws -> T) async throws -> T {
        if DispatchQueue.getSpecific(key: queueKey) == queueValue {
            return try await operation()
        } else {
            return try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    Task {
                        do {
                            let result = try await operation()
                            continuation.resume(returning: result)
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    }
                }
            }
        }
    }

    /// Throttled logging to reduce spam and UI lag
    private func throttledLog(_ message: String, throttleKey: String = "general", maxFrequency: TimeInterval = 1.0) {
        #if DEBUG
        let now = Date()

        if let lastTime = Self.lastLogTimes[throttleKey], now.timeIntervalSince(lastTime) < maxFrequency {
            return // Throttle this log message
        }

        Self.lastLogTimes[throttleKey] = now
        print("[SegmentRollingManager] \(message)")
        #endif
    }

    // MARK: - Initialization

    override init() {
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Starting initialization")
        #endif



        // Initialize segmentsDirectory
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Getting documents directory")
        #endif
        let documentsPaths = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)
        guard let documentsPath = documentsPaths.first else {
            // Fallback to temporary directory if documents directory is not available
            #if DEBUG
            print("[SegmentRollingManager] SegmentRollingManager.init() - Documents directory not available, using temporary directory")
            #endif
            segmentsDirectory = FileManager.default.temporaryDirectory.appendingPathComponent("AudioSegments")
            super.init()
            #if DEBUG
            print("[SegmentRollingManager] SegmentRollingManager.init() - Initialization completed with temporary directory")
            #endif



            // Set up queue-specific key for deadlock prevention
            queue.setSpecific(key: queueKey, value: queueValue)
            return
        }
        segmentsDirectory = documentsPath.appendingPathComponent("AudioSegments")
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Segments directory will be: \(segmentsDirectory.path)")
        #endif

        // Call super.init() before any throwing operations
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Calling super.init()")
        #endif
        super.init()
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - super.init() completed")
        #endif



        // Set up queue-specific key for deadlock prevention
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Setting up queue-specific key")
        #endif
        queue.setSpecific(key: queueKey, value: queueValue)
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Queue setup completed")
        #endif

        // Now perform directory creation (handle errors gracefully)
        log("SegmentRollingManager.init() - Creating segments directory")
        do {
            try FileManager.default.createDirectory(at: segmentsDirectory, withIntermediateDirectories: true, attributes: nil)
            log("SegmentRollingManager.init() - Segments directory created successfully")
        } catch {
            log("SegmentRollingManager.init() - Failed to create segments directory: \(error.localizedDescription)")
        }

        // Clean up any old segments from previous sessions
        log("SegmentRollingManager.init() - Starting cleanup of old segments")
        cleanupOldSegments()
        log("SegmentRollingManager.init() - Initialization completed successfully")
    }

    // MARK: - Public Methods

    /**
     * Start segment rolling recording
     * - parameters:
     *   - settings: Recording configuration (sampleRate, channels, bitrate)
     */
    func startSegmentRolling(with settings: [String: Any]) throws {
        log("startSegmentRolling called with settings: \(settings)")
        try performQueueSafeOperation {
            try startSegmentRollingInternal(with: settings)
        }
    }

    private func startSegmentRollingInternal(with settings: [String: Any]) throws {
        log("Inside queue.sync block")
        guard !isActive else {
            throw SegmentRecordingError.alreadyActive
        }

        // Store recording settings
        recordingSettings = settings
        log("Recording settings stored")

        // Configure audio session for recording
        log("Getting audio session instance")
        let audioSession = AVAudioSession.sharedInstance()
        log("Setting audio session category")
        // Configure category and mode for recording per checklist
        try audioSession.setCategory(.playAndRecord,
                                   mode: .default,
                                   options: [.allowBluetooth, .duckOthers])
        // Apply preferred hardware parameters
        if let sr = recordingSettings[AVSampleRateKey] as? Double {
            try? audioSession.setPreferredSampleRate(sr)
        }
        // 10–20 ms IO buffer for low latency and stable CPU
        try? audioSession.setPreferredIOBufferDuration(0.02)
        log("Activating audio session")
        try audioSession.setActive(true)
        recordingSession = audioSession

        // Set up audio session interruption handling
        setupAudioInterruptionHandling()



        // Reset state
        segmentCounter = 0
        segmentBuffer.removeAll()
        isActive = true
        totalRecordingDuration = 0
        recordingStartTime = Date()
        totalPausedDuration = 0
        pausedTime = nil
        isPaused = false

        // Determine recording mode based on maxDuration
        // Always use segment rolling

        log("Starting recording with segment rolling mode (maxDuration: \(maxDuration?.description ?? "unlimited"))")

        // Start continuous full-session recorder for instant file at stop
        try startContinuousRecorder()

        // Start recording
        log("About to call startNewSegment()")
        try startNewSegment()
        log("startNewSegment() completed successfully")

        // Start segment timer for rotating segments every segmentDuration (default 10 minutes)
        DispatchQueue.main.async {
            self.segmentTimer = Timer.scheduledTimer(withTimeInterval: AudioEngineConstants.segmentDuration, repeats: true) { _ in
                self.rotateSegment()
            }
        }
        log("Started segment rolling with 10-minute intervals")
    }

    /**
     * Pause segment rolling recording
     */
    func pauseSegmentRolling() {
        performQueueSafeOperation {
            guard isActive else { return }
            guard !isPaused else { return } // Already paused

            currentSegmentRecorder?.pause()
            // Pause continuous recorder as well
            continuousRecorder?.pause()

            // Record pause time
            pausedTime = Date()
            isPaused = true

            log("Pause - pausedTime set to: \(pausedTime!), totalPausedDuration before: \(totalPausedDuration)")

            // Stop segment timer
            segmentTimer?.invalidate()
            segmentTimer = nil

            log("Paused segment rolling recording")
        }
    }

    /**
     * Resume segment rolling recording
     */
    func resumeSegmentRolling() throws {
        try performQueueSafeOperation {
            guard isActive else {
                throw SegmentRecordingError.noRecordingToResume
            }
            guard isPaused else { return } // Not paused

            // Calculate and accumulate paused duration
            if let pauseStart = pausedTime {
                let pauseDuration = Date().timeIntervalSince(pauseStart)
                totalPausedDuration += pauseDuration
                log("Resume - pauseDuration: \(pauseDuration), totalPausedDuration after: \(totalPausedDuration)")
            }
            pausedTime = nil
            isPaused = false

            // Resume current segment or start new one if needed
            if let recorder = currentSegmentRecorder {
                if !recorder.record() {
                    // If can't resume, start new segment
                    try startNewSegment()
                }
            } else {
                try startNewSegment()
            }

            // Restart segment timer
            DispatchQueue.main.async {
                self.segmentTimer = Timer.scheduledTimer(withTimeInterval: AudioEngineConstants.segmentDuration, repeats: true) { _ in
                    self.rotateSegment()
                }
            }

            // Resume continuous recorder
            _ = self.continuousRecorder?.record()

            log("Resumed segment rolling recording")
        }
    }

    /**
     * Clear all segments and reset duration counters while maintaining active session
     * This method is used for reset functionality to clear segments but keep the microphone session active
     */
    func clearSegmentsAndReset() {
        performQueueSafeOperation {
            guard isActive else { return }

            log("Clearing all segments and resetting duration counters")

            // Stop and invalidate timer if running
            segmentTimer?.invalidate()
            segmentTimer = nil

            // Stop current segment recorder completely
            if let recorder = currentSegmentRecorder {
                if recorder.isRecording {
                    recorder.stop()
                }
                recorder.delegate = nil
                currentSegmentRecorder = nil
            }

            // Clear all segments from buffer and file system
            for segmentURL in segmentBuffer {
                do {
                    try FileManager.default.removeItem(at: segmentURL)
                    log("Removed segment: \(segmentURL.lastPathComponent)")
                } catch {
                    log("Failed to remove segment during reset: \(error.localizedDescription)")
                }
            }
            segmentBuffer.removeAll()

            // Reset all duration and timing counters
            segmentCounter = 0
            totalRecordingDuration = 0
            recordingStartTime = Date() // Reset to current time for fresh start
            totalPausedDuration = 0
            pausedTime = nil

            // Keep session active by starting a paused segment immediately to maintain microphone access
            // This ensures the microphone LED stays ON indicating active recording session
            do {
                try startNewSegment()
                // Immediately pause the segment to maintain session without recording
                if let newRecorder = currentSegmentRecorder {
                    newRecorder.pause()
                    isPaused = true
                    pausedTime = Date()
                    log("Started paused segment to maintain microphone session after reset")
                }
            } catch {
                log("Failed to maintain microphone session after reset: \(error.localizedDescription)")
                // If we can't maintain the session, set to inactive as fallback
                isActive = false
            }

            log("Segments cleared and duration reset - session remains active for microphone indicator")
        }
    }

    /**
     * Stop segment rolling and return final file (prefer continuous for 0s merge, fallback to merge)
     */
    func stopSegmentRolling() throws -> URL {
        return try performQueueSafeOperation {
            guard isActive else {
                throw SegmentRecordingError.noRecordingToStop
            }

            // Stop timer (async on main to avoid potential deadlocks when called from main thread)
            DispatchQueue.main.async {
                self.segmentTimer?.invalidate()
                self.segmentTimer = nil
            }

            // Stop current segment recording with aggressive cleanup
            if let recorder = currentSegmentRecorder {
                if recorder.isRecording {
                    recorder.stop()
                }
                recorder.delegate = nil
                currentSegmentRecorder = nil
            }

            var finalFileURL: URL

            log("Stopping segment rolling - attempting to use continuous recorder output for instant final file")

            // Attempt to stop continuous recorder and get its file
            if let continuousURL = stopContinuousRecorderAndReturnFile() {
                log("Continuous recording file ready: \(continuousURL.lastPathComponent)")
                // Apply trimming to last maxDuration seconds if configured
                if let maxDuration = maxDuration {
                    log("Trimming continuous file to last \(maxDuration)s for exact rolling window")
                    finalFileURL = try trimMergedFile(continuousURL, maxDuration: maxDuration)
                } else {
                    finalFileURL = continuousURL
                }
            } else {
                // Fallback: use segment merge path
                log("Continuous recorder unavailable - falling back to merging \(segmentBuffer.count) segments")

                // Add current segment to buffer if it exists and has content
                if let lastSegmentURL = getLastSegmentURL() {
                    addSegmentToBuffer(lastSegmentURL)
                }

                guard !segmentBuffer.isEmpty else {
                    log("Warning: No segments in buffer, this might be a very short recording")
                    throw SegmentRecordingError.noValidSegments
                }

                let mergedFileURL = try mergeSegments()
                if let maxDuration = maxDuration {
                    finalFileURL = try trimMergedFile(mergedFileURL, maxDuration: maxDuration)
                } else {
                    finalFileURL = mergedFileURL
                }
            }

            // Cleanup
            isActive = false
            totalRecordingDuration = 0
            recordingStartTime = nil
            totalPausedDuration = 0
            pausedTime = nil
            isPaused = false
            cleanupSegments()

            return finalFileURL
        }
    }

    /**
     * Get elapsed recording time across all segments
     * Returns total elapsed time since recording started (excluding paused time)
     */
    func getElapsedRecordingTime() -> TimeInterval {
        return performQueueSafeOperation {
            guard isActive else { return 0 }

            // For segment rolling, return total elapsed time since recording started minus paused time
            if let startTime = recordingStartTime {
                let totalElapsed = Date().timeIntervalSince(startTime)
                var currentPausedDuration = totalPausedDuration

                // If currently paused, add the current pause duration
                if isPaused, let pauseStart = pausedTime {
                    currentPausedDuration += Date().timeIntervalSince(pauseStart)
                }

                let actualRecordingTime = max(0, totalElapsed - currentPausedDuration)

                // Debug logging
                log("Duration calculation - totalElapsed: \(totalElapsed), totalPausedDuration: \(totalPausedDuration), currentPausedDuration: \(currentPausedDuration), actualRecordingTime: \(actualRecordingTime), isPaused: \(isPaused)")

                return actualRecordingTime
            } else {
                // Fallback to old calculation if startTime is somehow nil
                let segmentsDuration = Double(segmentBuffer.count) * AudioEngineConstants.segmentDuration
                let currentSegmentDuration = currentSegmentRecorder?.currentTime ?? 0
                return segmentsDuration + currentSegmentDuration
            }
        }
    }

    /**
     * Get the duration of audio currently buffered (available for processing)
     * This represents the actual audio that will be included in the final recording
     */
    func getBufferedAudioDuration() -> TimeInterval {
        return performQueueSafeOperation {
            guard isActive else { return getElapsedRecordingTime() }

            let segmentsDuration = Double(segmentBuffer.count) * AudioEngineConstants.segmentDuration
            let currentSegmentDuration = currentSegmentRecorder?.currentTime ?? 0
            return segmentsDuration + currentSegmentDuration
        }
    }



    /**
     * Check if segment rolling is currently active
     */
    func isSegmentRollingActive() -> Bool {
        return performQueueSafeOperation { isActive }
    }

        /**
     * Set maximum recording duration in seconds
     * Setting maxDuration enables automatic cleanup when buffer reaches capacity
     * Not setting maxDuration (nil) enables unlimited segment rolling recording
     */
    func setMaxDuration(_ duration: TimeInterval?) {
        performQueueSafeOperation {
            maxDuration = duration
            if let duration = duration {
                log("Set max duration to \(duration) seconds - segment rolling with automatic cleanup")
            } else {
                log("Set unlimited duration - segment rolling without cleanup")
            }
        }
    }

    /**
     * Set maximum recording duration in seconds (backwards compatibility)
     */
    func setMaxDuration(_ duration: TimeInterval) {
        setMaxDuration(duration as TimeInterval?)
    }

    /**
     * Stop segment rolling asynchronously (prefer continuous, fallback to merge)
     */
    func stopSegmentRollingAsync(completion: @escaping (Result<URL, Error>) -> Void) {
        // Perform the stop operation on a background queue to avoid QoS priority inversion
        Task.detached(priority: .utility) {
            do {
                let finalFileURL = try await self.performQueueSafeOperationAsync {
                    guard self.isActive else {
                        throw SegmentRecordingError.noRecordingToStop
                    }

                    // Stop timer
                    await MainActor.run {
                        self.segmentTimer?.invalidate()
                        self.segmentTimer = nil
                    }

                    // Stop current segment recording with aggressive cleanup
                    if let recorder = self.currentSegmentRecorder {
                        if recorder.isRecording {
                            recorder.stop()
                        }
                        recorder.delegate = nil
                        self.currentSegmentRecorder = nil
                    }

                    var finalFileURL: URL
                    self.log("Stopping segment rolling (async) - attempting to use continuous recorder output")

                    if let continuousURL = self.stopContinuousRecorderAndReturnFile() {
                        self.log("Continuous recording file ready: \(continuousURL.lastPathComponent)")
                        if let maxDuration = self.maxDuration {
                            self.log("Trimming continuous file to last \(maxDuration)s for exact rolling window (async)")
                            finalFileURL = try await self.trimMergedFileAsync(continuousURL, maxDuration: maxDuration)
                        } else {
                            finalFileURL = continuousURL
                        }
                    } else {
                        // Fallback: merge segments
                        self.log("Continuous recorder unavailable - falling back to merging \(self.segmentBuffer.count) segments (async)")
                        if let lastSegmentURL = self.getLastSegmentURL() {
                            self.addSegmentToBuffer(lastSegmentURL)
                        }
                        guard !self.segmentBuffer.isEmpty else {
                            self.log("Warning: No segments in buffer, this might be a very short recording")
                            throw SegmentRecordingError.noValidSegments
                        }
                        let mergedFileURL = try self.mergeSegments()
                        if let maxDuration = self.maxDuration {
                            finalFileURL = try await self.trimMergedFileAsync(mergedFileURL, maxDuration: maxDuration)
                        } else {
                            finalFileURL = mergedFileURL
                        }
                    }

                    // Cleanup state
                    self.isActive = false
                    self.segmentCounter = 0
                    self.totalRecordingDuration = 0
                    self.totalPausedDuration = 0
                    self.pausedTime = nil
                    self.isPaused = false

                    return finalFileURL
                }

                await MainActor.run {
                    completion(.success(finalFileURL))
                }
            } catch {
                await MainActor.run {
                    completion(.failure(error))
                }
            }
        }
    }

    // MARK: - Audio Session Interruption Handling

    /**
     * Set up AVAudioSession interruption handling to pause/resume recording safely
     */
    private func setupAudioInterruptionHandling() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionInterruption(_:)),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAudioSessionRouteChange(_:)),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )

        log("Audio interruption handling set up")
    }

        /**
     * Handle AVAudioSession interruptions (phone calls, Siri, etc.)
     * Strategy: Pause only for critical interruptions (calls), log and continue for others
     */
    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            // Determine interruption severity and decide whether to pause or continue
            let interruptionReason = determineInterruptionReason(userInfo)

            switch interruptionReason {
            case .phoneCall, .siri:
                log("Critical audio session interruption began (\(interruptionReason)) - pausing recording")
                pauseSegmentRolling()

                        case .systemNotification, .audioFocusLoss, .unknown:
                log("Non-critical audio session interruption began (\(interruptionReason)) - logging and continuing recording")
                throttledLog("Recording continues during \(interruptionReason) interruption",
                           throttleKey: "non_critical_interruption", maxFrequency: 10.0)

                // For camera scanning and other app activities, ensure audio session remains active
                do {
                    let audioSession = AVAudioSession.sharedInstance()
                    if !audioSession.isOtherAudioPlaying {
                        try audioSession.setActive(true)
                        log("Reactivated audio session during non-critical interruption")
                    }
                } catch {
                    log("Warning: Could not reactivate audio session: \(error.localizedDescription)")
                }
                // Continue recording without pausing
            }

        case .ended:
            log("Audio session interruption ended")
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    log("Resuming recording after interruption (if it was paused)")
                    do {
                        // Only try to resume if we're actually paused
                        if !isSegmentRollingActive() || getCurrentSegmentRecorder()?.isRecording == false {
                            // Reactivate audio session
                            try AVAudioSession.sharedInstance().setActive(true)
                            try resumeSegmentRolling()
                        } else {
                            log("Recording was not paused - no resume needed")
                        }
                    } catch {
                        log("Failed to resume recording after interruption: \(error.localizedDescription)")
                    }
                }
            }

        @unknown default:
            log("Unknown audio session interruption type: \(typeValue) - logging and continuing recording")
            throttledLog("Recording continues during unknown interruption type \(typeValue)",
                       throttleKey: "unknown_interruption", maxFrequency: 5.0)
        }
    }

    /**
     * Handle AVAudioSession route changes (headphone connect/disconnect, etc.)
     */
    @objc private func handleAudioSessionRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        throttledLog("Audio route changed: \(reason)", throttleKey: "route_change", maxFrequency: 5.0)

        switch reason {
        case .oldDeviceUnavailable:
            // Handle headphone disconnect or other device removal
            if let previousRoute = userInfo[AVAudioSessionRouteChangePreviousRouteKey] as? AVAudioSessionRouteDescription {
                for output in previousRoute.outputs {
                    if output.portType == .headphones || output.portType == .bluetoothA2DP {
                        log("Audio device disconnected, continuing recording on built-in speaker")
                        // Could pause/resume here if desired behavior is to stop on headphone disconnect
                        break
                    }
                }
            }

        case .newDeviceAvailable:
            log("New audio device available")

        case .categoryChange:
            log("Audio session category changed")

        case .override:
            log("Audio session override changed")

        case .unknown:
            log("Unknown audio route change reason")
            break
        case .wakeFromSleep:
            log("Wake from sleep")
            break
        case .noSuitableRouteForCategory:
            log("No suitable route for category")
            break
        case .routeConfigurationChange:
            log("Route configuration change")
            break
        @unknown default:
            log("Unknown audio route change reason: \(reason.rawValue)")
            break
        }
    }

    /**
     * Remove audio session interruption observers
     */
    private func removeAudioInterruptionHandling() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        log("Audio interruption handling removed")
    }

    /**
     * Determine the reason for interruption to decide on pause vs continue strategy
     */
    private func determineInterruptionReason(_ userInfo: [AnyHashable: Any]) -> InterruptionReason {
        log("Determining interruption reason from userInfo: \(userInfo)")

        // Check for specific interruption reasons if available (iOS 14.5+)
        if #available(iOS 14.5, *) {
            if let reasonValue = userInfo[AVAudioSessionInterruptionReasonKey] as? UInt {
                let reason = AVAudioSession.InterruptionReason(rawValue: reasonValue)
                log("iOS 14.5+ interruption reason value: \(reasonValue)")
                switch reason {
                case .builtInMicMuted:
                    log("Built-in mic muted interruption")
                    return .systemNotification
                case .default:
                    log("Default interruption reason")
                    break
                case .none:
                    log("No interruption reason")
                    break
                case .appWasSuspended:
                    log("App was suspended")
                    break
                case .routeDisconnected:
                    log("Route disconnected")
                    break
                @unknown default:
                    log("Unknown iOS 14.5+ interruption reason: \(reasonValue)")
                    break
                }
            }
        }

        // Enhanced phone call detection
        let audioSession = AVAudioSession.sharedInstance()

        // Method 1: Check if other audio is playing (phone call audio)
        if audioSession.isOtherAudioPlaying {
            log("Other audio is playing during interruption - likely phone call")
            return .phoneCall
        }

        // Method 2: Check current route for phone call indicators
        let currentRoute = audioSession.currentRoute
        log("Current audio route outputs: \(currentRoute.outputs.map { $0.portType.rawValue })")

        for output in currentRoute.outputs {
            if output.portType == .builtInReceiver {
                // Built-in receiver port is typically used during phone calls
                log("Audio route using built-in receiver port - indicating phone call")
                return .phoneCall
            }
        }

        // Method 3: Check for significant interruption duration (phone calls are longer)
        // This is a heuristic approach - we'll use a timer to check duration
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            // If interruption lasts more than 1 second, it's likely a phone call
            if AVAudioSession.sharedInstance().isOtherAudioPlaying {
                self.log("Long-duration interruption detected - likely phone call")
                // Update our detection if we're still interrupted
                if !self.isSegmentRollingActive() {
                    self.log("Retroactively detected phone call - should have paused recording")
                }
            }
        }

        // Try to infer from other context if available
        if let wasSuspended = userInfo[AVAudioSessionInterruptionWasSuspendedKey] as? Bool,
           wasSuspended {
            log("Audio session was suspended - audio focus loss")
            return .audioFocusLoss
        }

        // Enhanced default detection - assume phone calls for safety
        // Better to pause unnecessarily than to corrupt recording
        log("Could not determine specific interruption reason")
        log("Audio session category: \(audioSession.category.rawValue)")
        log("Audio session mode: \(audioSession.mode.rawValue)")

        // If we can't determine for sure, default to phone call for safety
        // This prevents file corruption at the cost of occasional unnecessary pauses
        log("Defaulting to phone call for safety (prevents file corruption)")
        return .phoneCall
    }

    /**
     * Interruption reason categories for handling strategy
     */
    private enum InterruptionReason {
        case phoneCall        // Always pause
        case siri            // Always pause
        case systemNotification  // Log and continue
        case audioFocusLoss  // Log and continue
        case unknown         // Log and continue

        var description: String {
            switch self {
            case .phoneCall: return "Phone Call"
            case .siri: return "Siri"
            case .systemNotification: return "System Notification"
            case .audioFocusLoss: return "Audio Focus Loss"
            case .unknown: return "Unknown"
            }
        }
    }

    /**
     * Get current segment recorder for interruption handling
     */
    private func getCurrentSegmentRecorder() -> AVAudioRecorder? {
        return performQueueSafeOperation {
            return currentSegmentRecorder
        }
    }

    // MARK: - Private Methods

    /**
     * Start recording a new segment with improved error handling and resource management
     */
    private func startNewSegment() throws {
        log("startNewSegment() - Getting segment URL for counter: \(segmentCounter)")
        let segmentURL = getSegmentURL(for: segmentCounter)
        log("startNewSegment() - Segment URL: \(segmentURL.path)")

        log("startNewSegment() - Creating AVAudioRecorder with settings: \(recordingSettings)")
        do {
            let recorder = try AVAudioRecorder(url: segmentURL, settings: recordingSettings)
            recorder.delegate = self

            // Use prepareToRecord() before attempting to record
            if !recorder.prepareToRecord() {
                log("startNewSegment() - prepareToRecord() failed")
                throw SegmentRecordingError.recordingStartFailed
            }

            currentSegmentRecorder = recorder
            log("startNewSegment() - AVAudioRecorder created and prepared successfully")
        } catch let error as SegmentRecordingError {
            log("startNewSegment() - SegmentRecordingError: \(error.localizedDescription)")
            throw error
        } catch {
            log("startNewSegment() - Failed to create AVAudioRecorder: \(error.localizedDescription)")
            throw SegmentRecordingError.recorderCreationFailed(error)
        }

        guard let recorder = currentSegmentRecorder else {
            log("startNewSegment() - Current segment recorder is nil after creation")
            throw SegmentRecordingError.recordingStartFailed
        }

        log("startNewSegment() - Starting recording on AVAudioRecorder")
        if !recorder.record() {
            log("startNewSegment() - recorder.record() returned false")
            // Clean up the failed recorder
            recorder.delegate = nil
            currentSegmentRecorder = nil
            throw SegmentRecordingError.recordingStartFailed
        }

        log("Started segment \(segmentCounter)")
    }

    /**
     * Rotate to next segment - called by timer (only in segment rolling mode)
     * Uses throttled logging to prevent UI lag
     */
    private func rotateSegment() {
        queue.async {
            guard self.isActive else { return }

            let totalElapsed = self.recordingStartTime.map { Date().timeIntervalSince($0) } ?? 0
            self.throttledLog("Rotating segment \(self.segmentCounter) after 10min (total elapsed: \(totalElapsed)s)",
                            throttleKey: "segment_rotation", maxFrequency: 5.0)

            // Stop current segment with aggressive cleanup
            if let recorder = self.currentSegmentRecorder {
                if recorder.isRecording {
                    recorder.stop()
                }
                recorder.delegate = nil

                // Add completed segment to buffer
                let segmentURL = self.getSegmentURL(for: self.segmentCounter)
                self.addSegmentToBuffer(segmentURL)
            }

            // Move to next segment
            self.segmentCounter += 1

            // Start new segment with error handling
            do {
                try self.startNewSegment()
            } catch let error as SegmentRecordingError {
                self.log("SegmentRecordingError during rotation: \(error.localizedDescription)")
                // Could add delegate callback here for error reporting
            } catch {
                self.log("Failed to start new segment: \(error.localizedDescription)")
            }
        }
    }

    /**
     * Add segment to buffer with rolling window management
     * Only used in segment rolling mode
     */
    private func addSegmentToBuffer(_ segmentURL: URL) {
        // Check if file exists and has content
        guard FileManager.default.fileExists(atPath: segmentURL.path) else {
            log("Segment file does not exist: \(segmentURL.lastPathComponent)")
            return
        }

        do {
            let attributes = try FileManager.default.attributesOfItem(atPath: segmentURL.path)
            let fileSize = attributes[.size] as? Int64 ?? 0

            // Only add segments with meaningful content
            guard fileSize > AudioEngineConstants.minValidFileSize else {
                log("Segment file too small (\(fileSize) bytes), skipping: \(segmentURL.lastPathComponent)")
                try? FileManager.default.removeItem(at: segmentURL)
                return
            }

            log("Adding segment to buffer: \(segmentURL.lastPathComponent) (\(fileSize) bytes)")
        } catch {
            log("Failed to get segment file attributes: \(error.localizedDescription)")
            return
        }

        // Add to buffer
        segmentBuffer.append(segmentURL)

        // Implement rolling window based on maxDuration
        let effectiveMaxSegments: Int
        if let maxDuration = maxDuration {
            // Calculate segments needed for maxDuration + buffer for trimming
            // We need enough segments to guarantee we can extract exactly maxDuration seconds
            // Add 1 extra segment to ensure we have enough audio for precise trimming
            let segmentsNeeded = Int(ceil(maxDuration / AudioEngineConstants.segmentDuration)) + 1
            effectiveMaxSegments = segmentsNeeded
            log("Max duration: \(maxDuration)s, segments needed: \(segmentsNeeded) (includes trimming buffer)")
        } else {
            // Unlimited when no maxDuration is provided (keep all segments)
            effectiveMaxSegments = Int.max
        }

        // Batch remove oldest segments if over limit to minimize filesystem overhead
        var segmentsToRemove: [URL] = []
        while segmentBuffer.count > effectiveMaxSegments {
            let oldestSegment = segmentBuffer.removeFirst()
            segmentsToRemove.append(oldestSegment)
        }

        // Batch delete old segment files
        if !segmentsToRemove.isEmpty {
            for segmentURL in segmentsToRemove {
                do {
                    try FileManager.default.removeItem(at: segmentURL)
                    throttledLog("Removed old segment: \(segmentURL.lastPathComponent)",
                               throttleKey: "segment_removal", maxFrequency: 2.0)
                } catch {
                    log("Failed to remove old segment: \(error.localizedDescription)")
                }
            }
            log("Batched removal of \(segmentsToRemove.count) old segments - maintaining rolling window of \(effectiveMaxSegments) segments")
        }

        if segmentBuffer.count % 5 == 0 || segmentBuffer.count >= effectiveMaxSegments {
            let totalDuration = recordingStartTime.map { Date().timeIntervalSince($0) } ?? 0
            log("Buffer status: \(segmentBuffer.count)/\(effectiveMaxSegments) segments (maxDuration: \(maxDuration?.description ?? "unlimited")s, total recording: \(totalDuration)s)")
        }
    }

    /**
     * Merge all segments in buffer into single audio file
     */
    private func mergeSegments() throws -> URL {
        log("Starting mergeSegments with \(segmentBuffer.count) segments")

        guard !segmentBuffer.isEmpty else {
            log("Error: No segments to merge")
            throw SegmentRecordingError.noValidSegments
        }

        // Create output file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let outputURL = documentsPath.appendingPathComponent("merged_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        log("Output URL: \(outputURL.path)")

        // Use AVAssetExportSession for high-quality merging
        let composition = AVMutableComposition()

        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            log("Error: Failed to create audio track for composition")
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var successfulSegments = 0

        // Add each segment to composition
        for (index, segmentURL) in segmentBuffer.enumerated() {
            log("Processing segment \(index): \(segmentURL.lastPathComponent)")
            let asset = AVAsset(url: segmentURL)

            guard let assetTrack = asset.tracks(withMediaType: .audio).first else {
                log("Warning: No audio track found in segment: \(segmentURL.lastPathComponent)")
                continue
            }

            let timeRange = CMTimeRange(start: .zero, duration: asset.duration)

            do {
                try audioTrack.insertTimeRange(timeRange, of: assetTrack, at: insertTime)
                insertTime = CMTimeAdd(insertTime, asset.duration)
                successfulSegments += 1
                log("Successfully added segment \(index) to composition")
            } catch {
                log("Warning: Failed to add segment \(index) to composition: \(error.localizedDescription)")
                continue
            }
        }

        guard successfulSegments > 0 else {
            log("Error: No segments could be added to composition")
            throw SegmentRecordingError.noValidSegments
        }

        log("Added \(successfulSegments) segments to composition, starting export...")

        // Export merged composition
        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            log("Error: Failed to create export session")
            throw SegmentRecordingError.exportSessionCreationFailed
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        // Use semaphore to wait for export completion
        let semaphore = DispatchSemaphore(value: 0)

        log("Starting export session...")
        exportSession.exportAsynchronously {
            semaphore.signal()
        }

        semaphore.wait()
        log("Export session completed with status: \(exportSession.status)")

        // Check for export errors after completion
        if exportSession.status == .failed {
            let error = exportSession.error ?? SegmentRecordingError.exportFailed("Unknown export failure")
            log("Export error: \(error.localizedDescription)")
            throw error
        }

        if exportSession.status != .completed {
            log("Export failed with status: \(exportSession.status)")
            throw SegmentRecordingError.exportFailed("\(exportSession.status)")
        }

        log("Successfully merged \(segmentBuffer.count) segments into: \(outputURL.lastPathComponent)")

        return outputURL
    }

    /**
     * Trim merged file to specified duration (synchronous version for compatibility)
     *
     * Assumptions in trimming logic:
     * - The rolling buffer maintains enough segments to ensure we can extract exactly maxDuration seconds
     * - We keep 1 extra segment beyond the required amount to guarantee sufficient audio for precise trimming
     * - We always extract the LAST maxDuration seconds from the merged file (not the first)
     * - This matches Android behavior for consistent cross-platform experience
     */
    private func trimMergedFile(_ sourceURL: URL, maxDuration: TimeInterval) throws -> URL {
        log("Starting trimMergedFile: source=\(sourceURL.lastPathComponent), maxDuration: \(maxDuration)")

        let asset = AVAsset(url: sourceURL)
        let duration = asset.duration.seconds
        log("Source file duration: \(duration) seconds")

        // The rolling buffer ensures we have enough audio to extract exactly maxDuration seconds
        // We always trim to extract the last maxDuration seconds, regardless of merged file length
        log("Extracting last \(maxDuration) seconds from merged file of \(duration) seconds")

        // If merged file is shorter than maxDuration, return the entire file
        if duration <= maxDuration {
            log("Merged file (\(duration)s) is shorter than or equal to maxDuration (\(maxDuration)s) - returning entire file")
            return sourceURL
        }

        // Create output URL for trimmed file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let trimmedURL = documentsPath.appendingPathComponent("trimmed_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        log("Trimmed file URL: \(trimmedURL.path)")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
            log("Removed existing trimmed file")
        }

        // Calculate start time to get the last N seconds (matching Android behavior)
        let startTime = CMTime(seconds: max(0, duration - maxDuration), preferredTimescale: 600)
        let endTime = CMTime(seconds: duration, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)
        log("Trim range: \(CMTimeGetSeconds(startTime)) to \(CMTimeGetSeconds(endTime)) seconds")

        // Create export session
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            log("Error: Failed to create export session for trimming")
            throw SegmentRecordingError.exportSessionCreationFailed
        }

        exportSession.outputURL = trimmedURL
        exportSession.outputFileType = .m4a
        exportSession.timeRange = timeRange

        // Use semaphore for synchronous operation (for compatibility)
        let semaphore = DispatchSemaphore(value: 0)
        var exportError: Error?
        var exportResult: URL?

        log("Starting trim export...")
        exportSession.exportAsynchronously { [weak exportSession] in
            guard let exportSession = exportSession else { return }

            switch exportSession.status {
            case .completed:
                self.log("Trim export completed successfully")
                exportResult = trimmedURL

            case .failed:
                let error = exportSession.error ?? SegmentRecordingError.trimExportFailed("Unknown trim export failure")
                self.log("Trim export error: \(error.localizedDescription)")
                exportError = error

            case .cancelled:
                self.log("Trim export was cancelled")
                exportError = SegmentRecordingError.trimExportFailed("Trim export was cancelled")

            default:
                self.log("Trim export failed with status: \(exportSession.status)")
                exportError = SegmentRecordingError.trimExportFailed("\(exportSession.status)")
            }
            semaphore.signal()
        }

        // Wait for export completion with timeout
        let timeoutResult = semaphore.wait(timeout: .now() + 30.0)

        if timeoutResult == .timedOut {
            exportSession.cancelExport()
            throw SegmentRecordingError.trimExportFailed("Trim export timed out after 30 seconds")
        }

        if let error = exportError {
            throw error
        }

        guard let result = exportResult else {
            throw SegmentRecordingError.trimExportFailed("Export completed but no result available")
        }

        log("Successfully trimmed merged file from \(duration)s to \(maxDuration)s (last \(maxDuration) seconds)")

        // Clean up original merged file
        try FileManager.default.removeItem(at: sourceURL)
        log("Cleaned up original merged file")

        return result
    }

    /**
     * Trim merged file to specified duration (async version with timeout)
     *
     * Assumptions in trimming logic:
     * - The rolling buffer maintains enough segments to ensure we can extract exactly maxDuration seconds
     * - We keep 1 extra segment beyond the required amount to guarantee sufficient audio for precise trimming
     * - We always extract the LAST maxDuration seconds from the merged file (not the first)
     * - This matches Android behavior for consistent cross-platform experience
     */
    private func trimMergedFileAsync(_ sourceURL: URL, maxDuration: TimeInterval) async throws -> URL {
        log("Starting trimMergedFile: source=\(sourceURL.lastPathComponent), maxDuration=\(maxDuration)")

        let asset = AVAsset(url: sourceURL)
        let duration = asset.duration.seconds
        log("Source file duration: \(duration) seconds")

        // The rolling buffer ensures we have enough audio to extract exactly maxDuration seconds
        // We always trim to extract the last maxDuration seconds, regardless of merged file length
        log("Extracting last \(maxDuration) seconds from merged file of \(duration) seconds")

        // If merged file is shorter than maxDuration, return the entire file
        if duration <= maxDuration {
            log("Merged file (\(duration)s) is shorter than or equal to maxDuration (\(maxDuration)s) - returning entire file")
            return sourceURL
        }

        // Create output URL for trimmed file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let trimmedURL = documentsPath.appendingPathComponent("trimmed_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        log("Trimmed file URL: \(trimmedURL.path)")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
            log("Removed existing trimmed file")
        }

        // Calculate start time to get the last N seconds (matching Android behavior)
        let startTime = CMTime(seconds: max(0, duration - maxDuration), preferredTimescale: 600)
        let endTime = CMTime(seconds: duration, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)
        log("Trim range: \(CMTimeGetSeconds(startTime)) to \(CMTimeGetSeconds(endTime)) seconds")

        // Create export session
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            log("Error: Failed to create export session for trimming")
            throw SegmentRecordingError.exportSessionCreationFailed
        }

        exportSession.outputURL = trimmedURL
        exportSession.outputFileType = .m4a
        exportSession.timeRange = timeRange

        // Use async completion with timeout instead of blocking semaphore
        let trimmedFileURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            self.log("Starting trim export...")

            // Create a flag to track if export completed
            var exportCompleted = false

            // Set up timeout
            let timeoutTask = DispatchWorkItem {
                if !exportCompleted {
                    self.log("Trim export timeout reached - cancelling export")
                    exportSession.cancelExport()
                    continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Trim export timed out after 30 seconds"))
                }
            }

            // Schedule timeout after 30 seconds
            DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 30.0, execute: timeoutTask)

            exportSession.exportAsynchronously {
                // Mark export as completed
                exportCompleted = true

                // Cancel timeout task since export completed
                timeoutTask.cancel()

                switch exportSession.status {
                case .completed:
                    self.log("Trim export completed successfully")
                    continuation.resume(returning: trimmedURL)

                case .failed:
                    let error = exportSession.error ?? SegmentRecordingError.trimExportFailed("Unknown trim export failure")
                    self.log("Trim export error: \(error.localizedDescription)")
                    continuation.resume(throwing: error)

                case .cancelled:
                    self.log("Trim export was cancelled")
                    continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Trim export was cancelled"))

                default:
                    self.log("Trim export failed with status: \(exportSession.status)")
                    continuation.resume(throwing: SegmentRecordingError.trimExportFailed("\(exportSession.status)"))
                }
            }
        }

        log("Successfully trimmed merged file from \(duration)s to \(maxDuration)s (last \(maxDuration) seconds)")

        // Clean up original merged file
        try FileManager.default.removeItem(at: sourceURL)
        log("Cleaned up original merged file")

        return trimmedFileURL
    }

    /**
     * Get URL for segment file
     */
    private func getSegmentURL(for index: Int) -> URL {
        return segmentsDirectory.appendingPathComponent("segment_\(index).m4a")
    }

    /**
     * Get URL of the last recorded segment
     */
    private func getLastSegmentURL() -> URL? {
        let segmentURL = getSegmentURL(for: segmentCounter)
        log("Checking for last segment: \(segmentURL.lastPathComponent)")

        if FileManager.default.fileExists(atPath: segmentURL.path) {
            do {
                let attributes = try FileManager.default.attributesOfItem(atPath: segmentURL.path)
                let fileSize = attributes[.size] as? Int64 ?? 0
                log("Last segment exists with size: \(fileSize) bytes")
                return segmentURL
            } catch {
                log("Error getting attributes for last segment: \(error.localizedDescription)")
                return nil
            }
        } else {
            log("Last segment file does not exist")
            return nil
        }
    }

    /**
     * Clean up all segment files with aggressive error handling
     */
    private func cleanupSegments() {
        var cleanupErrors: [Error] = []

        for segmentURL in segmentBuffer {
            do {
                try FileManager.default.removeItem(at: segmentURL)
                throttledLog("Cleaned up segment: \(segmentURL.lastPathComponent)",
                           throttleKey: "cleanup", maxFrequency: 1.0)
            } catch {
                log("Failed to cleanup segment: \(error.localizedDescription)")
                cleanupErrors.append(error)
            }
        }
        segmentBuffer.removeAll()
        // Log summary of cleanup issues if any
        if !cleanupErrors.isEmpty {
            log("Cleanup completed with \(cleanupErrors.count) errors out of \(segmentBuffer.count) segments")
        }
    }

    /**
     * Clean up old segments from previous sessions
     */
    private func cleanupOldSegments() {
        log("Starting cleanup of old segments from directory: \(segmentsDirectory.path)")

        // Check if directory exists first
        guard FileManager.default.fileExists(atPath: segmentsDirectory.path) else {
            log("Segments directory does not exist yet, skipping cleanup")
            return
        }

        do {
            log("Reading contents of segments directory")
            let contents = try FileManager.default.contentsOfDirectory(at: segmentsDirectory,
                                                                      includingPropertiesForKeys: nil,
                                                                      options: [])
            log("Found \(contents.count) items in segments directory")

            for fileURL in contents {
                if fileURL.pathExtension == "m4a" {
                    log("Removing old segment file: \(fileURL.lastPathComponent)")
                    try FileManager.default.removeItem(at: fileURL)
                    log("Successfully removed: \(fileURL.lastPathComponent)")
                }
            }

            log("Cleaned up old segments successfully")
        } catch {
            log("Failed to cleanup old segments: \(error.localizedDescription)")
            // Don't throw - this is just cleanup, shouldn't prevent initialization
        }
    }

    /**
     * Log helper
     */
    private func log(_ message: String) {
        #if DEBUG
        print("[SegmentRollingManager] \(message)")
        #endif
    }

    // MARK: - Continuous Recorder Helpers

    /// Start continuous full-session recorder for 0s merge at stop
    private func startContinuousRecorder() throws {
        // Build output file in Documents (parent of segments directory)
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let outputURL = documentsPath.appendingPathComponent("continuous_\(Int(Date().timeIntervalSince1970)).m4a")
        continuousOutputURL = outputURL

        // Release previous if any
        continuousRecorder?.stop()
        continuousRecorder = nil

        do {
            let recorder = try AVAudioRecorder(url: outputURL, settings: recordingSettings)
            if !recorder.prepareToRecord() {
                throw SegmentRecordingError.recordingStartFailed
            }
            // Start recording immediately
            if !recorder.record() {
                throw SegmentRecordingError.recordingStartFailed
            }
            continuousRecorder = recorder
            isContinuousActive = true
            log("Started continuous recorder: \(outputURL.lastPathComponent)")
        } catch {
            isContinuousActive = false
            continuousRecorder = nil
            throw SegmentRecordingError.recorderCreationFailed(error)
        }
    }

    /// Stop continuous recorder and return file URL if valid, else nil
    private func stopContinuousRecorderAndReturnFile() -> URL? {
        let url = continuousOutputURL
        if let recorder = continuousRecorder {
            if recorder.isRecording {
                recorder.stop()
            }
        }
        continuousRecorder = nil
        isContinuousActive = false

        if let url = url, FileManager.default.fileExists(atPath: url.path) {
            if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
               let size = attrs[.size] as? Int64, size > Int64(AudioEngineConstants.minValidFileSize) {
                return url
            }
        }
        return nil
    }

    // MARK: - Cleanup

    deinit {
        cleanup()
    }

    /// Comprehensive cleanup of all segment rolling resources with improved error handling
    func cleanup() {
        performQueueSafeOperation {
            if hasCleanedUp {
                log("SegmentRollingManager cleanup already performed - skipping")
                return
            }
            hasCleanedUp = true

            log("Starting SegmentRollingManager cleanup")

            // Stop timer with main queue safety
            DispatchQueue.main.async {
                self.segmentTimer?.invalidate()
                self.segmentTimer = nil
            }

            // Aggressive recorder cleanup - stop and deallocate immediately
            if let recorder = currentSegmentRecorder {
                if recorder.isRecording {
                    recorder.stop()
                }
                recorder.delegate = nil
                currentSegmentRecorder = nil
                log("Audio recorder stopped and deallocated")
            }
            // Remove audio interruption observers
            removeAudioInterruptionHandling()

            // Clean up temporary segment files
            cleanupSegments()

            // Stop and release continuous recorder as well
            if let recorder = continuousRecorder {
                if recorder.isRecording { recorder.stop() }
                continuousRecorder = nil
                isContinuousActive = false
                log("Continuous recorder stopped and deallocated")
            }

            // Harden audio session deactivation with retry logic
            if let session = recordingSession {
                let maxRetries = 3

                for attempt in 1...maxRetries {
                    do {
                        try session.setActive(false, options: .notifyOthersOnDeactivation)
                        recordingSession = nil
                        log("SegmentRollingManager audio session deactivated successfully on attempt \(attempt)")
                        break
                    } catch {
                        log("Warning: Failed to deactivate segment rolling audio session (attempt \(attempt)/\(maxRetries)): \(error.localizedDescription)")
                        if attempt == maxRetries {
                            log("Audio session deactivation failed after \(maxRetries) attempts - forcing nil assignment")
                            recordingSession = nil
                        } else {
                            // Brief delay before retry
                            Thread.sleep(forTimeInterval: 0.1)
                        }
                    }
                }
            }

            // Reset state
            isActive = false
            segmentCounter = 0
            totalRecordingDuration = 0
            recordingStartTime = nil
            totalPausedDuration = 0
            pausedTime = nil
            isPaused = false
            segmentBuffer.removeAll()
            recordingSettings.removeAll()

            log("SegmentRollingManager cleanup completed")
        }
    }
}

// MARK: - AVAudioRecorderDelegate

extension SegmentRollingManager: AVAudioRecorderDelegate {
    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        log("Segment finished recording, success: \(flag)")
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        if let error = error {
            log("Segment recording error: \(error.localizedDescription)")
        }
    }
}
