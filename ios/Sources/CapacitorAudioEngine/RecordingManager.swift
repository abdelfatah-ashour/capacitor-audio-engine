import Foundation
@preconcurrency import AVFoundation
import Compression

/// Delegate protocol for RecordingManager events
protocol RecordingManagerDelegate: AnyObject {
    /// Called when recording duration updates (every second during recording)
    /// - Parameter duration: Current recording duration in seconds
    func recordingDidUpdateDuration(_ duration: Int)

    /// Called when an error occurs during recording operations
    /// - Parameter error: The error that occurred
    func recordingDidEncounterError(_ error: Error)

    /// Called when recording finishes and file processing is complete
    /// - Parameter info: Dictionary containing file information, base64 data, and metadata
    func recordingDidFinish(_ info: [String: Any])

    /// Called when recording state changes (recording, paused, stopped)
    /// - Parameters:
    ///   - state: The new recording state
    ///   - data: Additional state information
    func recordingDidChangeState(_ state: String, data: [String: Any])
}

/**
 * RecordingManager handles audio recording operations with support for:
 * - Segment rolling for all recordings with automatic cleanup
 * - Asynchronous file processing to prevent UI blocking
 * - Compressed base64 generation with memory management
 * - Proper resource cleanup and error handling
 *
 * Performance characteristics:
 * - Non-blocking stopRecording() with async processing
 * - Memory-efficient base64 generation with streaming for large files
 * - Automatic resource cleanup on deallocation
 * - Thread-safe operations using dedicated dispatch queue
 */
class RecordingManager: NSObject {
    /// Delegate for receiving recording events
    weak var delegate: RecordingManagerDelegate?

    // MARK: - Recording Properties

    /// Audio session for recording configuration
    private var recordingSession: AVAudioSession?

    /// Current recording state
    private var isRecording = false

    /// Whether recording is currently paused
    private var isPaused = false

    /// Last reported duration to avoid duplicate callbacks
    private var lastReportedDuration: Double?

    /// Current recording duration in seconds
    private var currentDuration: Double = 0

    /// Timer for duration monitoring during recording
    private var durationDispatchSource: DispatchSourceTimer?

    // MARK: - Segment Rolling Properties

    /// Manager for segment rolling recordings
    private var segmentRollingManager: SegmentRollingManager?

    /// Maximum duration for segment rolling recordings
    private var maxDuration: TimeInterval?

    // MARK: - Configuration

    /// Stored recording settings for consistent retrieval after recording stops
    private var storedRecordingSettings: [String: Any] = [:]

    /// Last recording settings used for resuming after reset
    private var lastRecordingSettings: [String: Any]?

    // MARK: - Thread Safety & Async Processing

    /// Dedicated queue for thread-safe state operations
    private let stateQueue = DispatchQueue(label: "audio-engine-state", qos: .userInteractive)

    // MARK: - Initialization

    override init() {
        super.init()
    }

    /// Current async processing task for cancellation support
    private var processingTask: Task<Void, Never>?

    /// Initialize with delegate for event callbacks
    /// - Parameter delegate: Delegate to receive recording events
    init(delegate: RecordingManagerDelegate?) {
        super.init()
        self.delegate = delegate
    }

    deinit {
        cleanup()
    }

    // MARK: - Thread Safety

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        log("performStateOperation: About to enter stateQueue.sync")
        let result = try stateQueue.sync {
            log("performStateOperation: Inside stateQueue.sync, about to execute operation")
            let operationResult = try operation()
            log("performStateOperation: Operation completed successfully")
            return operationResult
        }
        log("performStateOperation: stateQueue.sync completed, returning result")
        return result
    }

    // MARK: - Recording Methods

    /**
     * Starts audio recording with the specified settings
     *
     * - Parameter settings: Recording configuration including:
     *   - sampleRate: Audio sample rate (Hz) - default 22050
     *   - channels: Number of audio channels - default 1 (mono)
     *   - bitrate: Audio bitrate (bps) - default 64000
     *   - maxDuration: Maximum recording duration for segment rolling (seconds) - optional
     *
     * Behavior:
     * - Uses segment rolling mode for all recordings
     * - If maxDuration is provided, enables automatic cleanup when buffer reaches capacity
     * - Configures audio session for recording
     * - Starts duration monitoring with 1-second intervals
     *
     * Thread Safety: All operations are performed on the state queue
     *
     * Delegate Callbacks:
     * - recordingDidUpdateDuration: Called every second with current duration
     * - recordingDidEncounterError: Called if recording fails to start
     */
    func startRecording(with settings: [String: Any]) {
        performStateOperation {
            // Store settings for potential resume after reset
            self.lastRecordingSettings = settings

            let sampleRate = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
            let channels = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
            let bitrate = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

            // Store the actual settings used for later retrieval
            self.storedRecordingSettings = [
                "sampleRate": sampleRate,
                "channels": channels,
                "bitrate": bitrate
            ]

            self.log("startRecording called with settings: \(settings)")
            self.log("Stored actual settings: \(self.storedRecordingSettings)")

            self.startRecordingInternal(settings: settings, sampleRate: sampleRate, channels: channels, bitrate: bitrate)
        }
    }

    private func startRecordingInternal(settings: [String: Any], sampleRate: Int, channels: Int, bitrate: Int) {

        // Check if maxDuration is provided to configure segment rolling behavior
        // Try multiple ways to extract maxDuration (could be Int, Double, or TimeInterval)
        var maxDurationValue: TimeInterval?

        if let intValue = settings["maxDuration"] as? Int {
            maxDurationValue = TimeInterval(intValue)
            log("maxDuration extracted as Int: \(intValue)")
        } else if let doubleValue = settings["maxDuration"] as? Double {
            maxDurationValue = doubleValue
            log("maxDuration extracted as Double: \(doubleValue)")
        } else if let timeIntervalValue = settings["maxDuration"] as? TimeInterval {
            maxDurationValue = timeIntervalValue
            log("maxDuration extracted as TimeInterval: \(timeIntervalValue)")
        } else {
            log("maxDuration not found or not a valid number type. Value: \(settings["maxDuration"] ?? "nil")")
        }

        if let duration = maxDurationValue, duration > 0 {
            maxDuration = duration
        }

        // Use segment rolling for all recordings
        log("Starting segment rolling recording (maxDuration: \(maxDuration?.description ?? "unlimited"))")
        startSegmentRollingRecording(with: settings)
    }

    private func startSegmentRollingRecording(with settings: [String: Any]) {
        log("Starting segment rolling recording...")

        do {
            // Initialize segment rolling manager if needed
            if self.segmentRollingManager == nil {
                self.log("Creating new SegmentRollingManager")
                self.log("About to call SegmentRollingManager() constructor")
                self.segmentRollingManager = SegmentRollingManager()
                self.log("SegmentRollingManager() constructor completed")
                self.log("SegmentRollingManager created successfully")
            }

            // Configure maxDuration for segment rolling
            if let maxDurationValue = self.maxDuration {
                self.log("Setting maxDuration to \(maxDurationValue) seconds in SegmentRollingManager (segment rolling mode)")
                self.segmentRollingManager?.setMaxDuration(maxDurationValue)
            } else {
                self.log("No maxDuration set - using unlimited segment rolling mode")
                self.segmentRollingManager?.setMaxDuration(nil)
            }

            // Create recording settings for segment rolling
            let sampleRate = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
            let channels = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
            let bitrate = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

            let recordingSettings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: Double(sampleRate),
                AVNumberOfChannelsKey: channels,
                AVEncoderBitRateKey: bitrate,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
            ]

            self.log("Starting segment rolling with settings: \(recordingSettings)")

            // Start segment rolling
            guard let segmentManager = self.segmentRollingManager else {
                throw NSError(domain: "RecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Segment rolling manager not initialized"])
            }
            try segmentManager.startSegmentRolling(with: recordingSettings)

            self.isRecording = true
            self.isPaused = false
            self.currentDuration = 0

            // Start duration monitoring for segment rolling
            self.startDurationMonitoring()

            // Notify delegate of state change to recording (consistent with Android)
            DispatchQueue.main.async {
                self.delegate?.recordingDidChangeState("recording", data: [
                    "duration": 0,
                    "isRecording": true,
                    "status": "recording"
                ])
            }

            self.log("Segment rolling recording started successfully")

        } catch {
            self.log("Failed to start segment rolling: \(error.localizedDescription)")
            self.delegate?.recordingDidEncounterError(error)
        }
    }

    func pauseRecording() {
        performStateOperation {
            guard self.isRecording else {
                self.log("No active recording to pause")
                return
            }

            // Pause segment rolling
            self.segmentRollingManager?.pauseSegmentRolling()

            self.isRecording = false
            self.isPaused = true
            self.stopDurationMonitoring()

            // Notify delegate of state change to paused (consistent with Android)
            DispatchQueue.main.async {
                self.delegate?.recordingDidChangeState("paused", data: [
                    "duration": self.currentDuration,
                    "isRecording": false,
                    "status": "paused"
                ])
            }

            self.log("Recording paused")
        }
    }

    func resumeRecording() {
        performStateOperation {
            guard !self.isRecording else {
                self.log("Recording is already active")
                return
            }

            guard self.isPaused else {
                self.log("No paused recording to resume")
                return
            }

            do {
                // Resume segment rolling
                if let segmentManager = self.segmentRollingManager {
                    // Check if this is a reset state (duration = 0) - start fresh
                    if self.currentDuration == 0 {
                        self.log("Starting fresh segment rolling after reset")

                        // Don't call cleanup() as it stops the microphone session
                        // Instead, start fresh segment rolling which will handle the restart
                        let settings = self.lastRecordingSettings ?? [:]

                        // Call the internal start method directly to avoid recursive calls
                        let sampleRate = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
                        let channels = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
                        let bitrate = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

                        // Set up recording settings
                        let recordingSettings: [String: Any] = [
                            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                            AVSampleRateKey: Double(sampleRate),
                            AVNumberOfChannelsKey: channels,
                            AVEncoderBitRateKey: bitrate,
                            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
                        ]

                        // Configure maxDuration for segment rolling
                        if let maxDurationValue = self.maxDuration {
                            segmentManager.setMaxDuration(maxDurationValue)
                        } else {
                            segmentManager.setMaxDuration(nil)
                        }

                        try segmentManager.startSegmentRolling(with: recordingSettings)
                        self.log("Fresh segment rolling started after reset")
                    } else {
                        // Normal resume from pause
                        try segmentManager.resumeSegmentRolling()
                    }
                } else {
                    // If segment rolling manager is nil (e.g., after reset), recreate it
                    self.log("Segment rolling manager not found, recreating for resume")
                    // Don't call self.startSegmentRollingRecording() directly to avoid potential issues
                    // Instead, recreate the segment rolling recording properly
                    let settings = self.lastRecordingSettings ?? [:]
                    let sampleRate = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
                    let channels = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
                    let bitrate = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

                    // Initialize segment rolling manager if needed
                    if self.segmentRollingManager == nil {
                        self.log("Creating new SegmentRollingManager for resume")
                        self.segmentRollingManager = SegmentRollingManager()
                        self.log("New SegmentRollingManager created")
                    }

                    // Ensure the manager is ready for use
                    guard let manager = self.segmentRollingManager else {
                        throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create segment rolling manager"])
                    }

                    // Configure maxDuration for segment rolling
                    if let maxDurationValue = self.maxDuration {
                        manager.setMaxDuration(maxDurationValue)
                    } else {
                        manager.setMaxDuration(nil)
                    }

                    // Create recording settings for segment rolling
                    let recordingSettings: [String: Any] = [
                        AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                        AVSampleRateKey: Double(sampleRate),
                        AVNumberOfChannelsKey: channels,
                        AVEncoderBitRateKey: bitrate,
                        AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
                    ]

                    try manager.startSegmentRolling(with: recordingSettings)
                    self.log("Segment rolling recording resumed with fresh session")
                }

                self.isRecording = true
                self.isPaused = false
                self.startDurationMonitoring()

                // Notify delegate of state change to recording (consistent with Android)
                DispatchQueue.main.async {
                    self.delegate?.recordingDidChangeState("recording", data: [
                        "duration": self.currentDuration,
                        "isRecording": true,
                        "status": "recording"
                    ])
                }

                self.log("Recording resumed")

            } catch {
                let nsError = error as NSError
                self.log("Failed to resume recording: \(nsError.localizedDescription)")
                self.delegate?.recordingDidEncounterError(nsError)
            }
        }
    }

    // resetRecording removed: to start fresh, stopRecording then startRecording

    /**
     * Stops the current recording and processes the audio file
     *
     * Performance Characteristics:
     * - Returns immediately (non-blocking)
     * - File processing happens asynchronously
     * - Base64 generation uses streaming for memory efficiency
     * - Supports cancellation of in-progress operations
     *
     * Processing Steps:
     * 1. Stop recording (segment rolling or linear)
     * 2. Merge segments if using segment rolling mode
     * 3. Calculate actual duration from audio file
     * 4. Generate compressed base64 data asynchronously
     * 5. Create response with file info and metadata
     *
     * Thread Safety: State changes are synchronized on state queue
     *
     * Delegate Callbacks:
     * - recordingDidFinish: Called when processing completes with file info
     * - recordingDidEncounterError: Called if stop/processing fails
     *
     * Error Conditions:
     * - No active recording
     * - File processing failure
     * - Compression/base64 generation failure
     * - Memory pressure during processing
     */
    func stopRecording() {
        performStateOperation {
            guard self.isRecording || self.isPaused else {
                self.log("No active recording to stop - already stopped or stopping")
                // Don't treat this as an error - just ignore duplicate stops gracefully
                return
            }

            // Stop monitoring immediately and set stopping state
            self.stopDurationMonitoring()
            self.isRecording = false
            self.isPaused = false

            self.log("Stopping segment rolling recording...")
            // Stop segment rolling and merge segments asynchronously to avoid blocking UI
            guard let segmentManager = self.segmentRollingManager else {
                self.log("Error: segmentRollingManager is nil")
                DispatchQueue.main.async {
                    let error = NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Segment rolling manager not initialized"])
                    self.delegate?.recordingDidEncounterError(error)
                }
                return
            }

            self.log("Calling stopSegmentRollingAsync on manager...")
            segmentManager.stopSegmentRollingAsync { [weak self] result in
                guard let self = self else { return }

                switch result {
                case .success(let mergedFileURL):
                    self.log("Segment rolling stopped successfully, processing file: \(mergedFileURL.path)")
                    self.processRecordingFile(mergedFileURL)

                case .failure(let error):
                    self.log("Error stopping segment rolling: \(error.localizedDescription)")

                    // Check if this is a "no valid segments" error (very short recording)
                    if error.localizedDescription.contains("No valid recording segments found") {
                        self.log("Segment rolling failed due to short recording")
                        DispatchQueue.main.async {
                            let error = NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Recording was too short"])
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    } else {
                        DispatchQueue.main.async {
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }
                }
            }

            // Reset common state
            self.resetRecordingState()
            self.log("Recording stopped")
        }
    }

    func getDuration() -> Int {
        return Int(segmentRollingManager?.getCurrentDuration() ?? currentDuration)
    }

    func getStatus() -> String {
        if isRecording {
            return "recording"
        } else if isPaused {
            return "paused"
        } else {
            return "idle"
        }
    }

    // MARK: - Audio Processing Methods

    func trimAudio(uri: String, start: Double, end: Double) async throws -> URL {
        // Convert the URI to a URL
        let sourceURL: URL
        if uri.hasPrefix("file://") {
            guard let url = URL(string: uri) else {
                throw NSError(domain: "AudioEngine", code: -1,
                             userInfo: [NSLocalizedDescriptionKey: "Invalid file URI"])
            }
            sourceURL = url
        } else {
            sourceURL = URL(fileURLWithPath: uri)
        }

        // Check if file exists
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            throw NSError(domain: "AudioEngine", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Source audio file does not exist"])
        }

        // Validate time range
        guard start >= 0 && end > start else {
            throw NSError(domain: "AudioEngine", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Invalid time range: start must be >= 0 and end must be > start"])
        }

        let asset = AVAsset(url: sourceURL)
        let assetDuration = asset.duration.seconds

        // Clamp end time to actual duration if it exceeds
        let actualEnd = min(end, assetDuration)

        // Validate that start time doesn't exceed the asset duration
        guard start < assetDuration else {
            throw NSError(domain: "AudioEngine", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Start time cannot exceed audio duration (\(assetDuration) seconds)"])
        }

        // Create output URL for trimmed file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let timestamp = Int(Date().timeIntervalSince1970)
        let trimmedURL = documentsPath.appendingPathComponent("trimmed_\(timestamp).m4a")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
        }

        // Create time range for trimming using clamped end time
        let startTime = CMTime(seconds: start, preferredTimescale: 600)
        let endTime = CMTime(seconds: actualEnd, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)

        // Create export session
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "AudioEngine", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Failed to create export session for trimming"])
        }

        exportSession.outputURL = trimmedURL
        exportSession.outputFileType = .m4a
        exportSession.timeRange = timeRange

        // Wait for export completion using continuation
        let finalURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            nonisolated(unsafe) let session = exportSession
            session.exportAsynchronously {
                if session.status == .failed {
                    if let error = session.error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(throwing: NSError(domain: "AudioEngine", code: -1,
                                                           userInfo: [NSLocalizedDescriptionKey: "Export failed with unknown error"]))
                    }
                } else if session.status == .completed {
                    continuation.resume(returning: trimmedURL)
                } else {
                    continuation.resume(throwing: NSError(domain: "AudioEngine", code: -1,
                                                       userInfo: [NSLocalizedDescriptionKey: "Audio trimming failed with status: \(session.status)"]))
                }
            }
        }

        log("Successfully trimmed audio from \(start)s to \(actualEnd)s, duration: \(actualEnd - start)s")
        return finalURL
    }

    /**
     * Trim audio file to specified duration
     * - parameters:
     *   - sourceURL: URL of the source audio file
     *   - maxDuration: Maximum duration in seconds
     * - returns: URL of the trimmed audio file
     */
    @available(iOS 13.0, *)
    private func trimAudioFile(_ sourceURL: URL, maxDuration: TimeInterval) async throws -> URL {
        let asset = AVAsset(url: sourceURL)
        let duration = asset.duration.seconds

        // If the file is already shorter than maxDuration, no trimming needed
        guard duration > maxDuration else {
            return sourceURL
        }

        // Create output URL for trimmed file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let trimmedURL = documentsPath.appendingPathComponent("trimmed_\(Int(Date().timeIntervalSince1970)).m4a")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: trimmedURL.path) {
            try FileManager.default.removeItem(at: trimmedURL)
        }

        // Create time range for trimming (last maxDuration seconds)
        let startTime = CMTime(seconds: max(0, duration - maxDuration), preferredTimescale: 600)
        let endTime = CMTime(seconds: duration, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startTime, end: endTime)

        // Create export session
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "AudioEngine", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Failed to create export session for trimming"])
        }

        exportSession.outputURL = trimmedURL
        exportSession.outputFileType = .m4a
        exportSession.timeRange = timeRange

        // Wait for export completion using continuation
        let finalURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            nonisolated(unsafe) let session = exportSession
            session.exportAsynchronously {
                if session.status == .failed {
                    if let error = session.error {
                        continuation.resume(throwing: error)
                    } else {
                        continuation.resume(throwing: NSError(domain: "AudioEngine", code: -1,
                                                           userInfo: [NSLocalizedDescriptionKey: "Export failed with unknown error"]))
                    }
                } else if session.status == .completed {
                    continuation.resume(returning: trimmedURL)
                } else {
                    continuation.resume(throwing: NSError(domain: "AudioEngine", code: -1,
                                                       userInfo: [NSLocalizedDescriptionKey: "Audio trimming failed with status: \(session.status)"]))
                }
            }
        }

        log("Successfully trimmed audio from \(duration)s to \(maxDuration)s (last \(maxDuration) seconds)")

        // Clean up original file
        try FileManager.default.removeItem(at: sourceURL)

        return finalURL
    }

    func switchMicrophone(to id: Int) {
        // Implementation for switching microphones
        log("Microphone switching not yet implemented")
    }

    func isMicrophoneBusy() -> (isBusy: Bool, reason: String) {
        return checkMicrophoneAvailabilityWithSystemAPIs()
    }

    func getAvailableMicrophones() -> [[String: Any]] {
        let audioSession = AVAudioSession.sharedInstance()
        let availableInputs = audioSession.availableInputs ?? []

        return availableInputs.map { input in
            return [
                "id": input.uid,
                "name": input.portName,
                "type": input.portType.rawValue
            ]
        }
    }

    // MARK: - Duration Monitoring

    private func startDurationMonitoring() {
        stopDurationMonitoring()

        log("Starting duration monitoring")

        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        dispatchSource.schedule(deadline: .now(), repeating: .seconds(Int(AudioEngineConstants.timerInterval)))
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self else { return }

            // Get duration from segment rolling manager
            let duration = self.segmentRollingManager?.getCurrentDuration() ?? 0
            self.currentDuration = duration

            self.lastReportedDuration = duration

            // Note: No auto-stop when maxDuration is reached - recording continues until manually stopped
            // This matches Android behavior where trimming happens during stopRecording()
            if let maxDuration = self.maxDuration, duration >= maxDuration {
                self.log("Duration (\(duration)s) has reached maxDuration (\(maxDuration)s) - recording continues, will trim on stop")
            }

            DispatchQueue.main.async {
                let integerDuration = Int(duration)
                self.delegate?.recordingDidUpdateDuration(integerDuration)
            }
        }

        self.durationDispatchSource = dispatchSource
        dispatchSource.resume()

        log("Duration monitoring started with dispatch source timer")
    }

    private func stopDurationMonitoring() {
        log("Stopping duration monitoring")

        if let dispatchSource = durationDispatchSource {
            dispatchSource.cancel()
            durationDispatchSource = nil
        }

        lastReportedDuration = nil
    }

    // MARK: - Helper Methods

        private func processRecordingFile(_ fileToReturn: URL) {
        log("Starting processRecordingFile for: \(fileToReturn.path)")

        // Cancel any existing processing task
        processingTask?.cancel()

        // Start new async processing task
        processingTask = Task {
            do {
                // Get basic file info
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileToReturn.path)
                let fileSize = fileAttributes[.size] as? Int64 ?? 0
                let modificationDate = fileAttributes[.modificationDate] as? Date ?? Date()

                self.log("File attributes - size: \(fileSize), modified: \(modificationDate)")

                // Check if task was cancelled
                try Task.checkCancellation()

                // Calculate duration asynchronously
                let asset = AVAsset(url: fileToReturn)
                let durationInSeconds = await asset.loadDurationAsync()

                self.log("Recording duration calculated: \(durationInSeconds) seconds")

                // Check if task was cancelled before expensive operation
                try Task.checkCancellation()

                // Generate compressed base64 asynchronously
                let compressedBase64 = try await self.generateCompressedBase64Async(from: fileToReturn)

                // Check if task was cancelled before creating response
                try Task.checkCancellation()

                // Create response with actual duration and compressed base64
                let response = self.createRecordingResponseWithDuration(
                    fileToReturn: fileToReturn,
                    fileSize: fileSize,
                    modificationDate: modificationDate,
                    durationInSeconds: durationInSeconds,
                    compressedBase64: compressedBase64
                )

                self.log("Created response with duration: \(durationInSeconds)s")

                // Return to main actor for delegate callback
                await MainActor.run {
                    self.log("Calling delegate recordingDidFinish")
                    self.delegate?.recordingDidFinish(response)
                }

            } catch is CancellationError {
                self.log("Recording processing was cancelled")
            } catch {
                self.log("Error in processRecordingFile: \(error.localizedDescription)")
                await MainActor.run {
                    self.delegate?.recordingDidEncounterError(error.asNSError)
                }
            }
        }
    }

    private func createRecordingResponse(fileToReturn: URL, fileSize: Int64, modificationDate: Date, durationInSeconds: Double) -> [String: Any] {
        let sampleRate = getSampleRate()
        let channels = getChannels()
        let bitrate = getBitrate()

        let roundedDuration = round(durationInSeconds * AudioEngineConstants.durationRoundingFactor) / AudioEngineConstants.durationRoundingFactor

        var base64Audio: String?
        do {
            let audioData = try Data(contentsOf: fileToReturn)
            let base64String = audioData.base64EncodedString()
            base64Audio = "data:audio/m4a;base64," + base64String
        } catch {
            log("Failed to encode audio file to base64: \(error.localizedDescription)")
            base64Audio = nil
        }

        return [
            "path": fileToReturn.path,
            "uri": fileToReturn.absoluteString,
            "webPath": "capacitor://localhost/_capacitor_file_" + fileToReturn.path,
            "mimeType": AudioEngineConstants.mimeTypeM4A,
            "size": fileSize,
            "duration": roundedDuration,
            "sampleRate": Int(sampleRate),
            "channels": channels,
            "bitrate": bitrate,
            "createdAt": Int(modificationDate.timeIntervalSince1970 * AudioEngineConstants.timestampMultiplier),
            "filename": fileToReturn.lastPathComponent,
            "base64": base64Audio ?? ""
        ]
    }

        /**
     * Generate compressed base64 string asynchronously from audio file
     * This provides faster response while maintaining quality
     */
    private func generateCompressedBase64Async(from fileURL: URL) async throws -> String {
        let startTime = CFAbsoluteTimeGetCurrent()

        return try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    // Read audio data efficiently
                    let audioData = try Data(contentsOf: fileURL)
                    let originalSize = audioData.count

                    // Check memory pressure
                    if originalSize > AudioEngineConstants.maxMemoryUsage {
                        throw RecordingError.memoryPressure
                    }

                    // Generate base64 with optional compression
                    Task {
                        do {
                            let base64String = try await audioData.base64StringWithOptionalCompression(useCompression: true)
                            let processingTime = CFAbsoluteTimeGetCurrent() - startTime

                            print("[RecordingManager] Base64 generation completed in \(String(format: "%.3f", processingTime))s")

                            continuation.resume(returning: base64String)
                        } catch {
                            continuation.resume(throwing: error)
                        }
                    }

                } catch {
                    print("[RecordingManager] Failed to generate compressed base64: \(error.localizedDescription)")
                    continuation.resume(throwing: RecordingError.compressionFailed(underlying: error))
                }
            }
        }
    }

    private func createRecordingResponseWithDuration(
        fileToReturn: URL,
        fileSize: Int64,
        modificationDate: Date,
        durationInSeconds: Double,
        compressedBase64: String
    ) -> [String: Any] {
        let sampleRate = getSampleRate()
        let channels = getChannels()
        let bitrate = getBitrate()

        let roundedDuration = round(durationInSeconds * AudioEngineConstants.durationRoundingFactor) / AudioEngineConstants.durationRoundingFactor

        return [
            "path": fileToReturn.path,
            "uri": fileToReturn.absoluteString,
            "webPath": "capacitor://localhost/_capacitor_file_" + fileToReturn.path,
            "mimeType": AudioEngineConstants.mimeTypeM4A,
            "size": fileSize,
            "duration": roundedDuration,
            "sampleRate": Int(sampleRate),
            "channels": channels,
            "bitrate": bitrate,
            "createdAt": Int(modificationDate.timeIntervalSince1970 * AudioEngineConstants.timestampMultiplier),
            "filename": fileToReturn.lastPathComponent,
            "base64": compressedBase64
        ]
    }



    private func getRecorderSettings() -> [String: Any] {
        // Use stored settings instead of trying to read from segment rolling manager
        return storedRecordingSettings.isEmpty ? getDefaultSettings() : storedRecordingSettings
    }

    private func getDefaultSettings() -> [String: Any] {
        return [
            "sampleRate": Int(AudioEngineConstants.defaultSampleRate),
            "channels": AudioEngineConstants.defaultChannels,
            "bitrate": AudioEngineConstants.defaultBitrate
        ]
    }

    private func getSampleRate() -> Double {
        return Double(storedRecordingSettings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate))
    }

    private func getChannels() -> Int {
        return storedRecordingSettings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
    }

    private func getBitrate() -> Int {
        return storedRecordingSettings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate
    }

    /// Comprehensive cleanup of all recording resources
    private func cleanup() {
        log("Starting comprehensive cleanup")



        // Cancel any running processing tasks
        processingTask?.cancel()
        processingTask = nil

        // Stop duration monitoring
        stopDurationMonitoring()

        // Clean up segment rolling manager
        segmentRollingManager?.cleanup()
        segmentRollingManager = nil

        // Remove interruption handling observers
        removeAudioInterruptionHandling()

        // Reset audio session
        if let session = recordingSession {
            do {
                try session.setActive(false, options: .notifyOthersOnDeactivation)
                recordingSession = nil
                log("Audio session deactivated")
            } catch {
                log("Warning: Failed to deactivate audio session: \(error.localizedDescription)")
            }
        }

        // Clear all state
        isRecording = false
        currentDuration = 0
        lastReportedDuration = nil
        storedRecordingSettings.removeAll()
        maxDuration = nil

        log("Cleanup completed")
    }

    private func resetRecordingState() {
        log("Resetting recording state")

        // Reset recording state
        currentDuration = 0
        lastReportedDuration = nil
        storedRecordingSettings.removeAll()

        // Stop duration monitoring
        stopDurationMonitoring()

        // Keep audio session active and configured for potential immediate playback
        // Don't reset to .playback with .mixWithOthers as it reduces volume
        if let session = recordingSession {
            do {
                // Maintain .playAndRecord configuration for seamless recording-to-playback transition
                try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
                // Keep session active for immediate playback capability
                try session.setActive(true)
                log("Audio session maintained for seamless playback transition")
            } catch {
                log("Warning: Failed to maintain audio session: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Microphone Availability

    private func checkMicrophoneAvailabilityWithSystemAPIs() -> (isBusy: Bool, reason: String) {
        let audioSession = AVAudioSession.sharedInstance()

        guard audioSession.recordPermission == .granted else {
            return (true, "Microphone permission not granted")
        }

        if #available(iOS 14.0, *) {
            do {
                try audioSession.setCategory(.playAndRecord, mode: .measurement, options: [.allowBluetooth, .defaultToSpeaker])
                try audioSession.setActive(true)

                let availableInputs = audioSession.availableInputs ?? []
                let _ = audioSession.preferredInput

                if availableInputs.isEmpty {
                    return (true, "No microphone inputs available")
                }

                for input in availableInputs {
                    if input.portType == .builtInMic {
                        do {
                            try audioSession.setPreferredInput(input)
                            return (false, "Microphone is available")
                        } catch {
                            return (true, "Cannot set preferred microphone input - may be in use")
                        }
                    }
                }

                return (false, "Microphone appears available")

            } catch {
                return (true, "Failed to configure audio session for microphone check")
            }
        } else {
            return checkMicrophoneAvailabilityLegacy()
        }
    }

    private func checkMicrophoneAvailabilityLegacy() -> (isBusy: Bool, reason: String) {
        let audioSession = AVAudioSession.sharedInstance()

        if audioSession.isOtherAudioPlaying {
            return (true, "Another app is playing audio")
        }

        let currentRoute = audioSession.currentRoute
        let inputs = currentRoute.inputs

        if inputs.isEmpty {
            return (true, "No audio inputs available")
        }

        for input in inputs {
            if input.portType == .builtInMic || input.portType == .headsetMic {
                let dataSources = input.dataSources ?? []
                if dataSources.isEmpty && input.selectedDataSource == nil {
                    return (true, "Microphone input may be in use by another app")
                }
            }
        }

        return (false, "Microphone appears available")
    }

    // MARK: - Audio Interruption Handling

    /**
     * Set up audio session interruption handling
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
     * Remove audio session interruption observers
     */
    private func removeAudioInterruptionHandling() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        log("Audio interruption handling removed")
    }

    /**
     * Handle AVAudioSession interruptions (phone calls, Siri, etc.)
     */
    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        log("Audio session interruption: \(type == .began ? "began" : "ended")")

        switch type {
        case .began:
            let interruptionReason = determineInterruptionReason(userInfo)
            log("Interruption reason: \(interruptionReason)")

            // Pause duration monitoring for ALL interruptions to ensure accurate timing
            stopDurationMonitoring()

            switch interruptionReason {
            case .phoneCall, .siri:
                log("Critical interruption (\(interruptionReason)) - pausing recording and duration")
                pauseRecording()

            case .systemNotification, .audioFocusLoss, .unknown:
                log("Non-critical interruption (\(interruptionReason)) - continuing recording but pausing duration")
                // Continue recording for non-critical interruptions, but duration is already paused above

                // Ensure audio session remains active for minor interruptions
                do {
                    let audioSession = AVAudioSession.sharedInstance()
                    if !audioSession.isOtherAudioPlaying {
                        try audioSession.setActive(true)
                        log("Reactivated audio session during non-critical interruption")
                    }
                } catch {
                    log("Warning: Could not reactivate audio session: \(error.localizedDescription)")
                }
            }

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    log("Resuming recording after interruption")
                    do {
                        // Reactivate audio session
                        try AVAudioSession.sharedInstance().setActive(true)

                        // Resume recording if it was paused (critical interruptions)
                        if !isRecording && isPaused {
                            resumeRecording()
                        } else if isRecording {
                            // For non-critical interruptions, just restart duration monitoring
                            log("Restarting duration monitoring after non-critical interruption")
                            startDurationMonitoring()
                        }
                    } catch {
                        log("Failed to resume recording after interruption: \(error.localizedDescription)")
                        delegate?.recordingDidEncounterError(error)
                    }
                } else {
                    // Even if we shouldn't resume recording, restart duration monitoring if recording is active
                    if isRecording {
                        log("Restarting duration monitoring after interruption (no resume)")
                        startDurationMonitoring()
                    }
                }
            }

        @unknown default:
            log("Unknown audio session interruption type: \(typeValue)")
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

        log("Audio route changed: \(reason.rawValue)")

        switch reason {
        case .oldDeviceUnavailable:
            log("Old audio device unavailable (e.g., headphones disconnected)")
            // Continue recording on built-in microphone

        case .newDeviceAvailable:
            log("New audio device available (e.g., headphones connected)")

        default:
            log("Other audio route change: \(reason.rawValue)")
        }
    }

    /**
     * Determine interruption reason - enhanced phone call detection
     */
    private func determineInterruptionReason(_ userInfo: [AnyHashable: Any]) -> InterruptionReason {
        log("Determining interruption reason from userInfo: \(userInfo)")

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
                log("Audio route using built-in receiver port - indicating phone call")
                return .phoneCall
            }
        }

        // For safety, default to phone call to prevent file corruption
        // Better to pause unnecessarily than to corrupt the recording
        log("Defaulting to phone call for safety (prevents file corruption)")
        return .phoneCall
    }

    /**
     * Interruption reason categories
     */
    private enum InterruptionReason {
        case phoneCall
        case siri
        case systemNotification
        case audioFocusLoss
        case unknown

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

    // MARK: - Utility Methods

    private func log(_ message: String) {
        #if DEBUG
        print("[RecordingManager] \(message)")
        #endif
    }
}
