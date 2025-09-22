import Foundation
import UIKit
@preconcurrency import AVFoundation

// Small, efficient circular buffer for URLs used to store segment file paths.
// Provides O(1) append and pop-front operations and Sequence conformance for iteration.
fileprivate struct CircularBuffer<Element>: Sequence {
    private var buffer: [Element?]
    private var head: Int = 0
    private var tail: Int = 0
    private(set) var count: Int = 0

    init(capacity: Int = 16) {
        let cap = Swift.max(1, capacity)
        buffer = Array<Element?>(repeating: nil, count: cap)
    }

    var isEmpty: Bool {
        return count == 0
    }

    mutating func append(_ element: Element) {
        if count == buffer.count {
            // grow
            let newCap = buffer.count * 2
            var newBuf = Array<Element?>(repeating: nil, count: newCap)
            for i in 0..<count {
                newBuf[i] = buffer[(head + i) % buffer.count]
            }
            buffer = newBuf
            head = 0
            tail = count % buffer.count
        }
        buffer[tail] = element
        tail = (tail + 1) % buffer.count
        count += 1
    }

    @discardableResult
    mutating func removeFirst() -> Element? {
        guard count > 0 else { return nil }
        let element = buffer[head]
        buffer[head] = nil
        head = (head + 1) % buffer.count
        count -= 1
        return element
    }

    mutating func removeAll() {
        buffer = Array<Element?>(repeating: nil, count: Swift.max(16, buffer.count))
        head = 0
        tail = 0
        count = 0
    }

    func makeIterator() -> AnyIterator<Element> {
        var idx = 0
        return AnyIterator {
            if idx >= self.count { return nil }
            let val = self.buffer[(self.head + idx) % self.buffer.count]!
            idx += 1
            return val
        }
    }

    func toArray() -> [Element] {
        var out: [Element] = []
        out.reserveCapacity(count)
        var idx = 0
        while idx < count {
            out.append(buffer[(head + idx) % buffer.count]!)
            idx += 1
        }
        return out
    }
}

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

    private var segmentBuffer = CircularBuffer<URL>(capacity: 16)
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

    // Config flags
    private var enableContinuousRecording: Bool = true

    // Pre-merge rolling file flags and state
    private var enablePreMergeRollingFile: Bool = true
    private var preferPCMForSegments: Bool = false
    private let mergeQueue = DispatchQueue(label: "segment-rolling-merge-queue", qos: .utility)
    private var isPreMergeInProgress: Bool = false
    private var preMergedOutputURL: URL?
    private var lastPreMergedSegmentIndex: Int = -1

    // Strict exact mode flags/state (defaults enabled)
    private var strictExactEnabled: Bool = true
    private enum ExactOutputFormat { case pcm, aac }
    private var exactOutputFormat: ExactOutputFormat = .aac
    private var backgroundPreEncodeEnabled: Bool = true
    private let exactQueue = DispatchQueue(label: "segment-rolling-exact-queue", qos: .utility)
    private var isExactBuildInProgress: Bool = false
    private var exactRollingAACURL: URL?

    // External audio session configuration (optional)
    private var externalAudioCategory: AVAudioSession.Category?
    private var externalAudioMode: AVAudioSession.Mode?
    private var externalAudioOptions: AVAudioSession.CategoryOptions?

    // Track temp output files for cleanup
    private var tempOutputFiles: [URL] = []

    // Duration control
    private var maxDuration: TimeInterval?
    // Per-instance segment duration (seconds). Default set from constants but configurable at runtime.
    private var segmentDuration: TimeInterval = AudioEngineConstants.segmentDuration

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

    /// Initialize with optional per-segment duration (seconds)
    convenience init(segmentDuration: TimeInterval?) {
        self.init()
        if let sd = segmentDuration, sd > 0 {
            self.setSegmentDuration(sd)
        }
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

        // Apply optional controls from settings with sensible defaults
        var effectiveSettings = settings

        // 1) Max duration (rolling window seconds)
        if let maxDur = settings["maxDuration"] as? Double {
            setMaxDuration(maxDur)
        }

        // 2) Segment duration override (seconds)
        if let segDur = settings["segmentDuration"] as? Double, segDur > 0 {
            setSegmentDuration(segDur)
        }

        // 3) Format choice: "aac" (default) or "pcm"
        if let formatChoice = (settings["format"] as? String)?.lowercased() {
            if formatChoice == "pcm" {
                setPreferPCMForSegments(true)
            } else {
                setPreferPCMForSegments(false)
            }
        }

        // 3b) Strict exact options with defaults (user-agreed)
        if let strict = settings["strictExact"] as? Bool {
            strictExactEnabled = strict
        } else {
            strictExactEnabled = true
        }
        if let exactFmt = (settings["exactOutputFormat"] as? String)?.lowercased() {
            exactOutputFormat = (exactFmt == "pcm") ? .pcm : .aac
        } else {
            exactOutputFormat = .aac
        }
        if let bg = settings["backgroundPreEncode"] as? Bool {
            backgroundPreEncodeEnabled = bg
        } else {
            backgroundPreEncodeEnabled = true
        }

        // 4) Recorder defaults if caller omitted
        if effectiveSettings[AVFormatIDKey] == nil {
            effectiveSettings[AVFormatIDKey] = kAudioFormatMPEG4AAC
        }
        if effectiveSettings[AVEncoderBitRateKey] == nil {
            effectiveSettings[AVEncoderBitRateKey] = NSNumber(value: AudioEngineConstants.defaultBitrate)
        }
        if effectiveSettings[AVSampleRateKey] == nil {
            effectiveSettings[AVSampleRateKey] = NSNumber(value: AudioEngineConstants.defaultSampleRate)
        }
        if effectiveSettings[AVNumberOfChannelsKey] == nil {
            effectiveSettings[AVNumberOfChannelsKey] = NSNumber(value: AudioEngineConstants.defaultChannels)
        }

        // Store recording settings
        recordingSettings = effectiveSettings
        log("Recording settings stored: format=\(String(describing: recordingSettings[AVFormatIDKey])) sr=\(String(describing: recordingSettings[AVSampleRateKey])) ch=\(String(describing: recordingSettings[AVNumberOfChannelsKey])) br=\(String(describing: recordingSettings[AVEncoderBitRateKey]))")

        // Configure audio session for recording
        log("Getting audio session instance")
        let audioSession = AVAudioSession.sharedInstance()
        log("Setting audio session category")
        // Configure category and mode for recording per checklist (allow external override)
        let category = self.externalAudioCategory ?? .playAndRecord
        let mode = self.externalAudioMode ?? .default
        // Use non-intrusive options by default: do not duck other audio; allow mixing and default to speaker
        let options = self.externalAudioOptions ?? [.allowBluetooth, .mixWithOthers, .defaultToSpeaker]
        try audioSession.setCategory(category, mode: mode, options: options)
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
        // Reset pre-merge state
        preMergedOutputURL = nil
        isPreMergeInProgress = false
        // Reset exact rolling state
        isExactBuildInProgress = false
        exactRollingAACURL = nil

        // Determine recording mode based on maxDuration
        // Always use segment rolling

        log("Starting recording with segment rolling mode (maxDuration: \(maxDuration?.description ?? "unlimited"))")

        // Start continuous full-session recorder for instant file at stop (optional)
        if enableContinuousRecording {
            try startContinuousRecorder()
        } else {
            log("Continuous recorder disabled via config flag")
        }

        // Start recording
        log("About to call startNewSegment()")
        try startNewSegment()
        log("startNewSegment() completed successfully")

        // Start segment timer for rotating segments every segmentDuration
        DispatchQueue.main.async {
            self.segmentTimer = Timer.scheduledTimer(withTimeInterval: self.segmentDuration, repeats: true) { _ in
                self.rotateSegment()
            }
        }
        log("Started segment rolling with segmentDuration = \(self.segmentDuration) seconds")
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
                self.segmentTimer = Timer.scheduledTimer(withTimeInterval: self.segmentDuration, repeats: true) { _ in
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
            // Iterate and remove files
            for segmentURL in segmentBuffer {
                do {
                    try FileManager.default.removeItem(at: segmentURL)
                    log("Removed segment: \(segmentURL.lastPathComponent)")
                } catch {
                    log("Failed to remove segment during reset: \(error.localizedDescription)")
                }
            }
            segmentBuffer.removeAll()

            // Reset pre-merge file
            if let preURL = preMergedOutputURL {
                try? FileManager.default.removeItem(at: preURL)
                preMergedOutputURL = nil
            }

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

            // Prefer exact rolling AAC (strictExact background) first for instant exact output
            if strictExactEnabled, exactOutputFormat == .aac, backgroundPreEncodeEnabled,
               let exactURL = exactRollingAACURL, FileManager.default.fileExists(atPath: exactURL.path) {
                log("Using exact rolling AAC for instant exact stop")
                finalFileURL = exactURL
            } else if let continuousURL = stopContinuousRecorderAndReturnFile() {
                log("Continuous recording file ready: \(continuousURL.lastPathComponent)")
                if let maxDuration = maxDuration {
                    log("Trimming continuous file to last \(maxDuration)s for exact rolling window")
                    finalFileURL = try trimMergedFile(continuousURL, maxDuration: maxDuration)
                } else {
                    finalFileURL = continuousURL
                }
            } else if enablePreMergeRollingFile, let preURL = preMergedOutputURL, FileManager.default.fileExists(atPath: preURL.path) {
                log("Using pre-merged rolling file path (continuous unavailable)")
                // If maxDuration set, ensure exact trim
                if let maxDuration = maxDuration {
                    finalFileURL = try trimMergedFile(preURL, maxDuration: maxDuration)
                } else {
                    finalFileURL = preURL
                }
            } else {
                // Fallback: quickly merge only the needed tail segments then trim
                log("Merging tail segments for finalization path")
                let segments = segmentBuffer.toArray()
                let neededCount: Int = {
                    if let maxD = maxDuration { return max(1, Int(ceil(maxD / self.segmentDuration)) + 1) }
                    return segments.count
                }()
                let tail = Array(segments.suffix(neededCount))
                let tailURL = try self.mergeSpecificSegments(tail)
                if let maxDuration = maxDuration {
                    finalFileURL = try self.trimMergedFile(tailURL, maxDuration: maxDuration)
                } else {
                    finalFileURL = tailURL
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

            // Do not delete the finalFileURL; cleanup temp pre-merge file reference
            if let preURL = preMergedOutputURL, preURL != finalFileURL {
                try? FileManager.default.removeItem(at: preURL)
            }
            preMergedOutputURL = nil

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
            let segmentsDuration = Double(segmentBuffer.count) * self.segmentDuration
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

            let segmentsDuration = Double(segmentBuffer.count) * self.segmentDuration
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

    /// Set per-segment duration (seconds). Applies to rotation timer and buffer calculations.
    func setSegmentDuration(_ duration: TimeInterval) {
        performQueueSafeOperation {
            guard duration > 0 else { return }
            segmentDuration = duration
            log("Segment duration set to \(segmentDuration) seconds")

            // If a timer exists, restart it with the new interval
            DispatchQueue.main.async {
                self.segmentTimer?.invalidate()
                self.segmentTimer = Timer.scheduledTimer(withTimeInterval: self.segmentDuration, repeats: true) { _ in
                    self.rotateSegment()
                }
            }
        }
    }

    /// Enable or disable using the continuous full-session recorder optimization
    func setContinuousRecordingEnabled(_ enabled: Bool) {
        performQueueSafeOperation { self.enableContinuousRecording = enabled }
    }

    /// Allow external configuration of the AVAudioSession category/mode/options
    func setAudioSession(category: AVAudioSession.Category?, mode: AVAudioSession.Mode? = nil, options: AVAudioSession.CategoryOptions? = nil) {
        performQueueSafeOperation {
            self.externalAudioCategory = category
            self.externalAudioMode = mode
            self.externalAudioOptions = options
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
                    // Ensure last segment is included
                    if let last = self.getLastSegmentURL() { self.addSegmentToBuffer(last) }

                    if self.strictExactEnabled, self.exactOutputFormat == .aac, self.backgroundPreEncodeEnabled,
                       let exactURL = self.exactRollingAACURL, FileManager.default.fileExists(atPath: exactURL.path) {
                        self.log("Using exact rolling AAC for instant exact stop (async)")
                        finalFileURL = exactURL
                    } else if let continuousURL = self.stopContinuousRecorderAndReturnFile() {
                        self.log("Continuous recording file ready: \(continuousURL.lastPathComponent)")
                        if let maxDuration = self.maxDuration {
                            self.log("Trimming continuous file to last \(maxDuration)s for exact rolling window (async)")
                            finalFileURL = try await self.trimMergedFileAsync(continuousURL, maxDuration: maxDuration)
                        } else {
                            finalFileURL = continuousURL
                        }
                    } else if self.enablePreMergeRollingFile, let preURL = self.preMergedOutputURL, FileManager.default.fileExists(atPath: preURL.path) {
                        self.log("Using pre-merged rolling file path (continuous unavailable, async)")
                        if let maxDuration = self.maxDuration {
                            finalFileURL = try await self.trimMergedFileAsync(preURL, maxDuration: maxDuration)
                        } else {
                            finalFileURL = preURL
                        }
                    } else {
                        // Fallback: merge segments
                        self.log("Continuous recorder unavailable - falling back to merging tail segments (async)")
                        guard !self.segmentBuffer.isEmpty else {
                            self.log("Warning: No segments in buffer, this might be a very short recording")
                            throw SegmentRecordingError.noValidSegments
                        }
                        let segments = self.segmentBuffer.toArray()
                        let neededCount: Int = {
                            if let maxD = self.maxDuration { return max(1, Int(ceil(maxD / self.segmentDuration)) + 1) }
                            return segments.count
                        }()
                        let tail = Array(segments.suffix(neededCount))
                        let mergedFileURL = try await self.mergeSpecificSegmentsAsync(tail)
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
                // Schedule background pre-merge update
                self.schedulePreMergeRollingFile()
                // Schedule exact background pre-encode if enabled
                self.scheduleExactRollingPreEncode()
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
            // Calculate segments needed for maxDuration + buffer for trimming based on per-instance segmentDuration
            // Add 1 extra segment to ensure we have enough audio for precise trimming
            let segmentsNeeded = Int(ceil(maxDuration / self.segmentDuration)) + 1
            effectiveMaxSegments = segmentsNeeded
            log("Max duration: \(maxDuration)s, segments needed: \(segmentsNeeded) (segmentDuration=\(self.segmentDuration)s, includes trimming buffer)")
        } else {
            // Unlimited when no maxDuration is provided (keep all segments)
            effectiveMaxSegments = Int.max
        }

        // Batch remove oldest segments if over limit to minimize filesystem overhead
        var segmentsToRemove: [URL] = []
        while segmentBuffer.count > effectiveMaxSegments {
            if let oldestSegment = segmentBuffer.removeFirst() {
                segmentsToRemove.append(oldestSegment)
            } else {
                break
            }
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

        // Schedule background pre-merge update whenever buffer changes
        schedulePreMergeRollingFile()
        // Schedule exact background pre-encode if enabled
        scheduleExactRollingPreEncode()
    }

    // MARK: - Pre-merge Rolling File

    /// Enable/disable pre-merge rolling file optimization
    func setPreMergeEnabled(_ enabled: Bool) {
        performQueueSafeOperation { self.enablePreMergeRollingFile = enabled }
    }

    /// Prefer PCM segments for gapless concatenation (future path with AVAudioEngine)
    func setPreferPCMForSegments(_ preferPCM: Bool) {
        performQueueSafeOperation { self.preferPCMForSegments = preferPCM }
    }

    /// Schedule building/updating the pre-merged rolling file in background
    private func schedulePreMergeRollingFile() {
        guard enablePreMergeRollingFile else { return }
        // Avoid piling up jobs; coalesce
        if isPreMergeInProgress { return }
        isPreMergeInProgress = true
        let snapshot = performQueueSafeOperation { () -> ([URL], TimeInterval?, TimeInterval) in
            let urls = segmentBuffer.toArray()
            return (urls, maxDuration, segmentDuration)
        }

        mergeQueue.async {
            Task {
                defer { self.isPreMergeInProgress = false }
                do {
                    let outputURL = try await self.buildPreMergedRollingFileAsync(segments: snapshot.0, maxDuration: snapshot.1, segmentDuration: snapshot.2)

                    // Atomically publish the new pre-merged file
                    self.performQueueSafeOperation {
                        // Track for later cleanup
                        if !self.tempOutputFiles.contains(outputURL) { self.tempOutputFiles.append(outputURL) }
                        self.preMergedOutputURL = outputURL
                    }
                } catch {
                    self.log("Pre-merge build failed: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Schedule maintaining an exact rolling AAC file (strictExact + backgroundPreEncode)
    private func scheduleExactRollingPreEncode() {
        guard strictExactEnabled, exactOutputFormat == .aac, backgroundPreEncodeEnabled else { return }
        if isExactBuildInProgress { return }
        guard let maxD = maxDuration else { return }

        // Prefer continuous file as source; if not available yet, skip this cycle
        guard let sourceURL = continuousOutputURL, FileManager.default.fileExists(atPath: sourceURL.path) else { return }

        isExactBuildInProgress = true
        let sampleRate = (recordingSettings[AVSampleRateKey] as? NSNumber)?.doubleValue ?? AudioEngineConstants.defaultSampleRate

        exactQueue.async {
            Task {
                defer { self.isExactBuildInProgress = false }
                do {
                    let exactURL = try await self.buildExactRollingAACAsync(sourceURL: sourceURL, maxDuration: maxD, sampleRate: sampleRate)
                    self.performQueueSafeOperation {
                        if !self.tempOutputFiles.contains(exactURL) { self.tempOutputFiles.append(exactURL) }
                        self.exactRollingAACURL = exactURL
                    }
                } catch {
                    self.log("Exact background pre-encode failed: \(error.localizedDescription)")
                }
            }
        }
    }

    /// Build an exact-length AAC rolling file by re-encoding last maxDuration seconds
    private func buildExactRollingAACAsync(sourceURL: URL, maxDuration: TimeInterval, sampleRate: Double) async throws -> URL {
        let asset = AVAsset(url: sourceURL)
        let duration = asset.duration.seconds
        guard duration > 0 else { throw SegmentRecordingError.noValidSegments }

        // Choose a slightly early start to ensure enough frames, then we will rely on writer packet sizes to closely match
        let startTime = CMTime(seconds: max(0, duration - maxDuration), preferredTimescale: CMTimeScale(sampleRate))
        let endTime = CMTime(seconds: duration, preferredTimescale: CMTimeScale(sampleRate))
        let timeRange = CMTimeRange(start: startTime, end: endTime)

        // Reuse fast reader/writer path
        let tempDir = FileManager.default.temporaryDirectory
        let outURL = tempDir.appendingPathComponent("exact_rolling_\(Int(Date().timeIntervalSince1970)).m4a")
        if FileManager.default.fileExists(atPath: outURL.path) { try? FileManager.default.removeItem(at: outURL) }
        let url = try await trimMergedFileFastAsync(sourceURL, trimmedURL: outURL, timeRange: timeRange)
        return url
    }

    /// Build the pre-merged rolling file to reflect the current buffer (takes last maxDuration seconds if set)
    private func buildPreMergedRollingFileAsync(segments: [URL], maxDuration: TimeInterval?, segmentDuration: TimeInterval) async throws -> URL {
        guard !segments.isEmpty else { throw SegmentRecordingError.noValidSegments }

        // Select subset covering last maxDuration seconds if provided
        let selectedSegments: [URL]
        if let maxD = maxDuration {
            let neededCount = max(1, Int(ceil(maxD / segmentDuration)) + 1)
            selectedSegments = Array(segments.suffix(neededCount))
        } else {
            selectedSegments = segments
        }

        // Compose and export to temp file
        let tempDir = FileManager.default.temporaryDirectory
        let tempOut = tempDir.appendingPathComponent("rolling_premerged_\(Int(Date().timeIntervalSince1970)).m4a")

        // Build composition
        let composition = AVMutableComposition()
        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var added = 0
        for url in selectedSegments {
            let asset = AVAsset(url: url)
            if let track = asset.tracks(withMediaType: .audio).first {
                let range = CMTimeRange(start: .zero, duration: asset.duration)
                do {
                    try audioTrack.insertTimeRange(range, of: track, at: insertTime)
                    insertTime = CMTimeAdd(insertTime, asset.duration)
                    added += 1
                } catch {
                    log("Pre-merge: failed to insert segment \(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
        }
        guard added > 0 else { throw SegmentRecordingError.noValidSegments }

        // Try passthrough, then AppleM4A
        func runExport(preset: String, fileType: AVFileType?) async throws -> URL {
            guard let export = AVAssetExportSession(asset: composition, presetName: preset) else {
                throw SegmentRecordingError.exportSessionCreationFailed
            }
            export.outputURL = tempOut
            if let ft = fileType { export.outputFileType = ft }
            else if export.supportedFileTypes.contains(.m4a) { export.outputFileType = .m4a }
            return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<URL, Error>) in
                export.exportAsynchronously {
                    switch export.status {
                    case .completed:
                        cont.resume(returning: tempOut)
                    case .failed:
                        cont.resume(throwing: export.error ?? SegmentRecordingError.exportFailed("Unknown export failure"))
                    case .cancelled:
                        cont.resume(throwing: SegmentRecordingError.exportFailed("Export cancelled"))
                    default:
                        cont.resume(throwing: SegmentRecordingError.exportFailed("\(export.status)"))
                    }
                }
            }
        }

        do {
            return try await runExport(preset: AVAssetExportPresetPassthrough, fileType: nil)
        } catch {
            log("Pre-merge passthrough failed: \(error.localizedDescription). Falling back to AppleM4A.")
            // Remove partial
            if FileManager.default.fileExists(atPath: tempOut.path) { try? FileManager.default.removeItem(at: tempOut) }
            return try await runExport(preset: AVAssetExportPresetAppleM4A, fileType: .m4a)
        }
    }

    /**
     * Merge all segments in buffer into single audio file
     */
    private func mergeSegments() throws -> URL {
        // Legacy synchronous merge retained for backward compatibility
        log("Starting mergeSegments with \(segmentBuffer.count) segments")

        guard !segmentBuffer.isEmpty else {
            log("Error: No segments to merge")
            throw SegmentRecordingError.noValidSegments
        }

        // Create output file in temp directory
        let tempDir = FileManager.default.temporaryDirectory
        let outputURL = tempDir.appendingPathComponent("merged_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(outputURL)
        log("Output URL: \(outputURL.path)")

        // Use AVAssetExportSession for high-quality merging
        let composition = AVMutableComposition()

        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            log("Error: Failed to create audio track for composition")
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var successfulSegments = 0

        // Add each segment to composition in chronological order
        let segments = segmentBuffer.toArray()
        for (index, segmentURL) in segments.enumerated() {
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

        // Export merged composition synchronously (legacy). Prefer using async mergeSegmentsAsync().
        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            log("Error: Failed to create export session")
            throw SegmentRecordingError.exportSessionCreationFailed
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        let semaphore = DispatchSemaphore(value: 0)
        log("Starting export session...")
        exportSession.exportAsynchronously { semaphore.signal() }
        semaphore.wait()
        log("Export session completed with status: \(exportSession.status)")
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

    /// Merge only specific segment URLs (synchronous)
    private func mergeSpecificSegments(_ segments: [URL]) throws -> URL {
        log("Starting mergeSpecificSegments with \(segments.count) segments")
        guard !segments.isEmpty else { throw SegmentRecordingError.noValidSegments }

        let tempDir = FileManager.default.temporaryDirectory
        let outputURL = tempDir.appendingPathComponent("merged_tail_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(outputURL)

        let composition = AVMutableComposition()
        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var added = 0
        for url in segments {
            let asset = AVAsset(url: url)
            if let track = asset.tracks(withMediaType: .audio).first {
                let range = CMTimeRange(start: .zero, duration: asset.duration)
                do {
                    try audioTrack.insertTimeRange(range, of: track, at: insertTime)
                    insertTime = CMTimeAdd(insertTime, asset.duration)
                    added += 1
                } catch {
                    log("Failed to insert segment: \(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
        }
        guard added > 0 else { throw SegmentRecordingError.noValidSegments }

        guard let export = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw SegmentRecordingError.exportSessionCreationFailed
        }
        export.outputURL = outputURL
        export.outputFileType = .m4a

        let semaphore = DispatchSemaphore(value: 1)
        semaphore.wait()
        export.exportAsynchronously { semaphore.signal() }
        semaphore.wait()
        if export.status != .completed {
            throw SegmentRecordingError.exportFailed("\(export.status)")
        }
        return outputURL
    }

    /**
     * Merge all segments asynchronously without blocking the thread. Tries passthrough first, then falls back to AppleM4A.
     */
    private func mergeSegmentsAsync() async throws -> URL {
        log("Starting mergeSegmentsAsync with \(segmentBuffer.count) segments")
        guard !segmentBuffer.isEmpty else { throw SegmentRecordingError.noValidSegments }

        // Output url in temp dir
        let tempDir = FileManager.default.temporaryDirectory
        let outputURL = tempDir.appendingPathComponent("merged_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(outputURL)

        // Build composition
        let composition = AVMutableComposition()
        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var successfulSegments = 0
        for segmentURL in segmentBuffer.toArray() {
            let asset = AVAsset(url: segmentURL)
            if let assetTrack = asset.tracks(withMediaType: .audio).first {
                let timeRange = CMTimeRange(start: .zero, duration: asset.duration)
                do {
                    try audioTrack.insertTimeRange(timeRange, of: assetTrack, at: insertTime)
                    insertTime = CMTimeAdd(insertTime, asset.duration)
                    successfulSegments += 1
                } catch {
                    log("Failed to add segment to composition: \(error.localizedDescription)")
                }
            }
        }
        guard successfulSegments > 0 else { throw SegmentRecordingError.noValidSegments }

        // Helper to run an export asynchronously
        func runExport(preset: String, fileType: AVFileType?) async throws -> URL {
            guard let exportSession = AVAssetExportSession(asset: composition, presetName: preset) else {
                throw SegmentRecordingError.exportSessionCreationFailed
            }
            exportSession.outputURL = outputURL
            if let ft = fileType {
                exportSession.outputFileType = ft
            } else if exportSession.supportedFileTypes.contains(.m4a) {
                exportSession.outputFileType = .m4a
            } else if let first = exportSession.supportedFileTypes.first {
                exportSession.outputFileType = first
            }
            return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
                exportSession.exportAsynchronously {
                    switch exportSession.status {
                    case .completed:
                        continuation.resume(returning: outputURL)
                    case .failed:
                        continuation.resume(throwing: exportSession.error ?? SegmentRecordingError.exportFailed("Unknown export failure"))
                    case .cancelled:
                        continuation.resume(throwing: SegmentRecordingError.exportFailed("Export cancelled"))
                    default:
                        continuation.resume(throwing: SegmentRecordingError.exportFailed("\(exportSession.status)"))
                    }
                }
            }
        }

        // Try passthrough first, then fallback
        do {
            return try await runExport(preset: AVAssetExportPresetPassthrough, fileType: nil)
        } catch {
            log("Passthrough export failed: \(error.localizedDescription). Falling back to AppleM4A.")
            // Remove partially written file if exists
            if FileManager.default.fileExists(atPath: outputURL.path) {
                try? FileManager.default.removeItem(at: outputURL)
            }
            return try await runExport(preset: AVAssetExportPresetAppleM4A, fileType: .m4a)
        }
    }

    /// Merge only specific segment URLs (async)
    private func mergeSpecificSegmentsAsync(_ segments: [URL]) async throws -> URL {
        guard !segments.isEmpty else { throw SegmentRecordingError.noValidSegments }
        let tempDir = FileManager.default.temporaryDirectory
        let outputURL = tempDir.appendingPathComponent("merged_tail_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(outputURL)

        let composition = AVMutableComposition()
        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw SegmentRecordingError.audioTrackCreationFailed
        }

        var insertTime = CMTime.zero
        var added = 0
        for url in segments {
            let asset = AVAsset(url: url)
            if let track = asset.tracks(withMediaType: .audio).first {
                let range = CMTimeRange(start: .zero, duration: asset.duration)
                do {
                    try audioTrack.insertTimeRange(range, of: track, at: insertTime)
                    insertTime = CMTimeAdd(insertTime, asset.duration)
                    added += 1
                } catch {
                    log("Failed to insert segment: \(url.lastPathComponent): \(error.localizedDescription)")
                }
            }
        }
        guard added > 0 else { throw SegmentRecordingError.noValidSegments }

        func runExport(preset: String, fileType: AVFileType?) async throws -> URL {
            guard let export = AVAssetExportSession(asset: composition, presetName: preset) else {
                throw SegmentRecordingError.exportSessionCreationFailed
            }
            export.outputURL = outputURL
            if let ft = fileType { export.outputFileType = ft }
            else if export.supportedFileTypes.contains(.m4a) { export.outputFileType = .m4a }
            return try await withCheckedThrowingContinuation { (cont: CheckedContinuation<URL, Error>) in
                export.exportAsynchronously {
                    switch export.status {
                    case .completed: cont.resume(returning: outputURL)
                    case .failed: cont.resume(throwing: export.error ?? SegmentRecordingError.exportFailed("Unknown export failure"))
                    case .cancelled: cont.resume(throwing: SegmentRecordingError.exportFailed("Export cancelled"))
                    default: cont.resume(throwing: SegmentRecordingError.exportFailed("\(export.status)"))
                    }
                }
            }
        }

        do { return try await runExport(preset: AVAssetExportPresetPassthrough, fileType: nil) }
        catch {
            log("Tail merge passthrough failed: \(error.localizedDescription). Falling back to AppleM4A.")
            if FileManager.default.fileExists(atPath: outputURL.path) { try? FileManager.default.removeItem(at: outputURL) }
            return try await runExport(preset: AVAssetExportPresetAppleM4A, fileType: .m4a)
        }
    }

    /// Compose final by remerging tail segments (guarantees last partial included) then trim
    private func composePreMergedPlusLatestAndTrim(preMergedURL: URL, maxDuration: TimeInterval?) throws -> URL {
        let segments = segmentBuffer.toArray()
        let neededCount: Int = {
            if let maxD = maxDuration { return max(1, Int(ceil(maxD / self.segmentDuration)) + 1) }
            return segments.count
        }()
        let tail = Array(segments.suffix(neededCount))
        let tailURL = try mergeSpecificSegments(tail)
        if let maxD = maxDuration { return try trimMergedFile(tailURL, maxDuration: maxD) }
        return tailURL
    }

    private func composePreMergedPlusLatestAndTrimAsync(preMergedURL: URL, maxDuration: TimeInterval?) async throws -> URL {
        let segments = segmentBuffer.toArray()
        let neededCount: Int = {
            if let maxD = maxDuration { return max(1, Int(ceil(maxD / self.segmentDuration)) + 1) }
            return segments.count
        }()
        let tail = Array(segments.suffix(neededCount))
        let tailURL = try await mergeSpecificSegmentsAsync(tail)
        if let maxD = maxDuration { return try await trimMergedFileAsync(tailURL, maxDuration: maxD) }
        return tailURL
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

        // Create output URL for trimmed file in temp directory
        let tempDir = FileManager.default.temporaryDirectory
        let trimmedURL = tempDir.appendingPathComponent("trimmed_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(trimmedURL)
        log("Trimmed file URL: \(trimmedURL.path)")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
            log("Removed existing trimmed file")
        }

        // Safety pad accounts for encoder priming/gap (~50-100 ms). We'll trim a tiny bit extra then export exact range.
        let safetyPad: Double = 0.1
        let startTime = CMTime(seconds: max(0, duration - maxDuration - safetyPad), preferredTimescale: 600)
        let endTime = CMTime(seconds: duration, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)
        log("Trim range (with pad): \(CMTimeGetSeconds(startTime)) to \(CMTimeGetSeconds(endTime)) seconds")

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

    // Create output URL for trimmed file in temp directory
        let tempDir = FileManager.default.temporaryDirectory
        let trimmedURL = tempDir.appendingPathComponent("trimmed_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        tempOutputFiles.append(trimmedURL)
        log("Trimmed file URL: \(trimmedURL.path)")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
            log("Removed existing trimmed file")
        }

        // Safety pad for encoder priming/gap (~50-100ms)
        let safetyPad: Double = 0.1
        let startTime = CMTime(seconds: max(0, duration - maxDuration - safetyPad), preferredTimescale: 600)
        let endTime = CMTime(seconds: duration, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)
        log("Trim range (with pad): \(CMTimeGetSeconds(startTime)) to \(CMTimeGetSeconds(endTime)) seconds")

        // Helper to try export with preset and timeout
        func tryExport(_ preset: String, _ fileType: AVFileType?) async throws -> URL {
            guard let exportSession = AVAssetExportSession(asset: asset, presetName: preset) else {
                throw SegmentRecordingError.exportSessionCreationFailed
            }
            exportSession.outputURL = trimmedURL
            if let ft = fileType {
                exportSession.outputFileType = ft
            } else if exportSession.supportedFileTypes.contains(.m4a) {
                exportSession.outputFileType = .m4a
            } else if let first = exportSession.supportedFileTypes.first {
                exportSession.outputFileType = first
            }
            exportSession.timeRange = timeRange

            return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
                self.log("Starting trim export with preset: \(preset)...")

                var completed = false
                let timeout = DispatchWorkItem {
                    if !completed {
                        self.log("Trim export timeout - cancelling export")
                        exportSession.cancelExport()
                        continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Trim export timed out after 30 seconds"))
                    }
                }
                DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + 30.0, execute: timeout)

                exportSession.exportAsynchronously {
                    completed = true
                    timeout.cancel()
                    switch exportSession.status {
                    case .completed:
                        continuation.resume(returning: trimmedURL)
                    case .failed:
                        continuation.resume(throwing: exportSession.error ?? SegmentRecordingError.trimExportFailed("Unknown trim export failure"))
                    case .cancelled:
                        continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Export cancelled"))
                    default:
                        continuation.resume(throwing: SegmentRecordingError.trimExportFailed("\(exportSession.status)"))
                    }
                }
            }
        }

        // 1) Passthrough first
        do {
            let url = try await tryExport(AVAssetExportPresetPassthrough, nil)
            self.log("Trim completed via passthrough (no re-encode)")
            try? FileManager.default.removeItem(at: sourceURL)
            return url
        } catch {
            self.log("Passthrough trim failed: \(error.localizedDescription). Trying AppleM4A…")
        }

        // 2) AppleM4A fallback
        do {
            let url = try await tryExport(AVAssetExportPresetAppleM4A, .m4a)
            self.log("Trim completed via AppleM4A export")
            try? FileManager.default.removeItem(at: sourceURL)
            return url
        } catch {
            self.log("AppleM4A trim failed: \(error.localizedDescription). Falling back to reader/writer…")
        }

        // 3) Last resort: reader/writer (re-encode)
        let fastURL = try await trimMergedFileFastAsync(sourceURL, trimmedURL: trimmedURL, timeRange: timeRange)
        self.log("Trim completed via reader/writer (re-encode)")
        try? FileManager.default.removeItem(at: sourceURL)
        return fastURL
    }

    /**
     Fast trim using AVAssetReader -> AVAssetWriter with minimal transcoding/passthrough.
     Returns trimmedURL on success or throws on failure.
     */
    private func trimMergedFileFastAsync(_ sourceURL: URL, trimmedURL: URL, timeRange: CMTimeRange) async throws -> URL {
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            DispatchQueue.global(qos: .utility).async {
                let asset = AVAsset(url: sourceURL)

                guard let reader = try? AVAssetReader(asset: asset) else {
                    continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Failed to create AVAssetReader"))
                    return
                }

                guard let audioTrack = asset.tracks(withMediaType: .audio).first else {
                    continuation.resume(throwing: SegmentRecordingError.audioTrackCreationFailed)
                    return
                }

                // Configure reader output for the requested timeRange
                let readerOutputSettings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatLinearPCM
                ]
                let readerOutput = AVAssetReaderTrackOutput(track: audioTrack, outputSettings: readerOutputSettings)
                reader.timeRange = timeRange

                if reader.canAdd(readerOutput) {
                    reader.add(readerOutput)
                } else {
                    continuation.resume(throwing: SegmentRecordingError.trimExportFailed("Cannot add reader output"))
                    return
                }

                // Writer with AAC output at reasonable bitrate
                guard let writer = try? AVAssetWriter(outputURL: trimmedURL, fileType: .m4a) else {
                    continuation.resume(throwing: SegmentRecordingError.exportSessionCreationFailed)
                    return
                }

                let writerOutputSettings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVEncoderBitRateKey: NSNumber(value: AudioEngineConstants.defaultBitrate),
                    AVSampleRateKey: NSNumber(value: AudioEngineConstants.defaultSampleRate),
                    AVNumberOfChannelsKey: NSNumber(value: AudioEngineConstants.defaultChannels)
                ]

                let writerInput = AVAssetWriterInput(mediaType: .audio, outputSettings: writerOutputSettings)
                writerInput.expectsMediaDataInRealTime = false

                if writer.canAdd(writerInput) {
                    writer.add(writerInput)
                } else {
                    continuation.resume(throwing: SegmentRecordingError.exportSessionCreationFailed)
                    return
                }

                writer.shouldOptimizeForNetworkUse = true

                if !writer.startWriting() {
                    continuation.resume(throwing: SegmentRecordingError.exportFailed("Writer failed to start"))
                    return
                }

                writer.startSession(atSourceTime: timeRange.start)

                if !reader.startReading() {
                    writer.cancelWriting()
                    continuation.resume(throwing: SegmentRecordingError.exportFailed("Reader failed to start"))
                    return
                }

                let inputQueue = DispatchQueue(label: "trim-writer-queue")

                writerInput.requestMediaDataWhenReady(on: inputQueue) {
                    while writerInput.isReadyForMoreMediaData {
                        if let sampleBuffer = readerOutput.copyNextSampleBuffer() {
                            if !writerInput.append(sampleBuffer) {
                                reader.cancelReading()
                                writerInput.markAsFinished()
                                writer.cancelWriting()
                                continuation.resume(throwing: SegmentRecordingError.exportFailed("Failed to append sample buffer"))
                                return
                            }
                        } else {
                            writerInput.markAsFinished()
                            writer.finishWriting {
                                if writer.status == .completed {
                                    continuation.resume(returning: trimmedURL)
                                } else {
                                    let err = writer.error ?? SegmentRecordingError.exportFailed("Unknown writer error")
                                    continuation.resume(throwing: err)
                                }
                            }
                            break
                        }
                    }
                }
            }
        }
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
        let initialCount = segmentBuffer.count

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
            log("Cleanup completed with \(cleanupErrors.count) errors out of \(initialCount) segments")
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
        // Build output file in temporary directory
        let tempDir = FileManager.default.temporaryDirectory
        let outputURL = tempDir.appendingPathComponent("continuous_\(Int(Date().timeIntervalSince1970)).m4a")
        continuousOutputURL = outputURL
        tempOutputFiles.append(outputURL)

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
                // Ensure tracked for cleanup
                if !self.tempOutputFiles.contains(url) {
                    self.tempOutputFiles.append(url)
                }
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

            // Clean up any temp output files (merged/trimmed/continuous)
            for url in tempOutputFiles {
                try? FileManager.default.removeItem(at: url)
            }
            tempOutputFiles.removeAll()
            if let preURL = preMergedOutputURL {
                try? FileManager.default.removeItem(at: preURL)
                preMergedOutputURL = nil
            }

            // Stop and release continuous recorder as well
            if let recorder = continuousRecorder {
                if recorder.isRecording { recorder.stop() }
                continuousRecorder = nil
                isContinuousActive = false
                log("Continuous recorder stopped and deallocated")
            }

            // Harden audio session deactivation with retry logic using async delay to avoid blocking
            if let session = recordingSession {
                let maxRetries = 3
                func attemptDeactivate(_ attempt: Int) {
                    do {
                        try session.setActive(false, options: .notifyOthersOnDeactivation)
                        recordingSession = nil
                        log("SegmentRollingManager audio session deactivated successfully on attempt \(attempt)")
                    } catch {
                        log("Warning: Failed to deactivate segment rolling audio session (attempt \(attempt)/\(maxRetries)): \(error.localizedDescription)")
                        if attempt < maxRetries {
                            // Schedule next retry asynchronously to avoid blocking
                            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.1) {
                                attemptDeactivate(attempt + 1)
                            }
                        } else {
                            log("Audio session deactivation failed after \(maxRetries) attempts - forcing nil assignment")
                            recordingSession = nil
                        }
                    }
                }
                attemptDeactivate(1)
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
