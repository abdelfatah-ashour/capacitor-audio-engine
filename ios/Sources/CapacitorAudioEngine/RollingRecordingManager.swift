import Foundation
@preconcurrency import AVFoundation

/// Delegate protocol for RollingRecordingManager events
protocol RollingRecordingManagerDelegate: AnyObject {
    /// Called when recording duration updates (every second during recording)
    /// - Parameter duration: Current recording duration in seconds
    func recordingDidUpdateDuration(_ duration: Int)

    /// Called when an error occurs during recording operations
    /// - Parameter error: The error that occurred
    func recordingDidEncounterError(_ error: Error)

    /// Called when recording finishes and file processing is complete
    /// - Parameter info: Dictionary containing file information and metadata
    func recordingDidFinish(_ info: [String: Any])

    /// Called when recording state changes (recording, paused, stopped)
    /// - Parameters:
    ///   - state: The new recording state
    ///   - data: Additional state information
    func recordingDidChangeState(_ state: String, data: [String: Any])

    /// Called when a segment is ready (for debugging/monitoring)
    /// - Parameters:
    ///   - segmentIndex: Index of the completed segment
    ///   - duration: Duration of the completed segment
    func recordingDidCompleteSegment(_ segmentIndex: Int, duration: Double)
}

/**
 * RollingRecordingManager implements the iOS segment rolling strategy:
 *
 * Core Strategy:
 * 1. **Segment Rolling**: Record into short segments (60s) using AVAssetWriter
 * 2. **Rolling Buffer**: Store segments in a queue (Deque) and drop oldest when maxDuration exceeded
 * 3. **Pre-Merge**: Background task incrementally merges segments into a pre-merged file
 * 4. **Instant Stop**: On stop, simply return the latest pre-merged file (no additional processing)
 *
 * Performance Characteristics:
 * - Non-blocking stopRecording() - always < 1 second regardless of recording duration
 * - Memory efficient - maintains rolling window instead of growing indefinitely
 * - Background processing - segment merging happens asynchronously
 * - Thread-safe operations using dedicated dispatch queues
 *
 * Implementation Details:
 * - Uses AVAssetWriter for compressed format (AAC in .m4a)
 * - Maintains ⌈maxDuration/segment_length⌉ + 1 files max
 * - Background merge replaces old pre-merged file atomically
 */
class RollingRecordingManager: NSObject {
    /// Delegate for receiving recording events
    weak var delegate: RollingRecordingManagerDelegate?

    // MARK: - Rolling Segment Properties

    /// Current recording state
    private var isRecording = false

    /// Whether recording is currently paused
    private var isPaused = false

    /// Whether a reset operation is in progress
    private var isResetting = false

    /// Last reported duration to avoid duplicate callbacks
    private var lastReportedDuration: Double?

    /// Current recording duration in seconds
    private var currentDuration: Double = 0

    /// Timer for duration monitoring during recording
    private var durationDispatchSource: DispatchSourceTimer?

    // MARK: - Segment Management

    /// Array of completed segment URLs (rolling buffer)
    private var segmentURLs: [URL] = []

    /// Current active segment being recorded
    private var currentSegmentWriter: AVAssetWriter?

    /// Current segment input for writing audio data
    private var currentSegmentInput: AVAssetWriterInput?

    /// Current segment index
    private var currentSegmentIndex: Int = 0

    /// Duration of current segment in progress
    private var currentSegmentDuration: Double = 0

    /// Start time of current segment
    private var currentSegmentStartTime: Date?

    // MARK: - Rolling Window Configuration

    /// Maximum total duration to keep (rolling window size)
    private var maxDuration: TimeInterval?

    /// Length of each segment in seconds (fixed at 1 minute for optimal performance)
    private let segmentLength: TimeInterval = AudioEngineConstants.segmentLength

    /// Maximum number of segments to keep (calculated from maxDuration/segmentLength)
    private var maxSegments: Int = 0

    // MARK: - Pre-Merge Management

    /// Background queue for pre-merge operations
    private let mergeQueue = DispatchQueue(label: "rolling-recording-merge", qos: .utility)

    /// URL of the current pre-merged file
    private var preMergedURL: URL?

    /// Whether a merge operation is currently in progress
    private var isMerging = false

    /// Current merge task for cancellation support
    private var mergeTask: Task<Void, Never>?

    // MARK: - Configuration

    /// Audio session for recording configuration
    private var recordingSession: AVAudioSession?

    /// Stored recording settings for consistent retrieval after recording stops
    private var storedRecordingSettings: [String: Any] = [:]

    /// Last recording settings used for resuming after reset
    private var lastRecordingSettings: [String: Any]?

    // MARK: - Thread Safety

    /// Dedicated queue for thread-safe state operations
    private let stateQueue = DispatchQueue(label: "rolling-recording-state", qos: .userInteractive)

    /// Queue for segment operations
    private let segmentQueue = DispatchQueue(label: "rolling-recording-segments", qos: .userInteractive)

    // MARK: - Initialization

    override init() {
        super.init()
    }

    /// Initialize with delegate for event callbacks
    /// - Parameter delegate: Delegate to receive recording events
    init(delegate: RollingRecordingManagerDelegate?) {
        super.init()
        self.delegate = delegate
    }

    deinit {
        cleanup()
    }

    // MARK: - Thread Safety

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    private func performSegmentOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try segmentQueue.sync { try operation() }
    }

    // MARK: - Recording Methods

    /**
     * Starts rolling segment recording with the specified settings
     *
     * - Parameter settings: Recording configuration including:
     *   - sampleRate: Audio sample rate (Hz) - default 22050
     *   - channels: Number of audio channels - default 1 (mono)
     *   - bitrate: Audio bitrate (bps) - default 64000
     *   - maxDuration: Optional: maximum duration to keep (rolling window)
     *
     * Behavior:
     * - Uses AVAssetWriter to record compressed format (AAC in .m4a)
     * - Creates rolling segments with configurable length
     * - Maintains pre-merged file in background for instant stop
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

            // Extract rolling window configuration
            if let maxDurationValue = settings["maxDuration"] as? Int {
                self.maxDuration = TimeInterval(maxDurationValue)
            } else if let maxDurationValue = settings["maxDuration"] as? Double {
                self.maxDuration = TimeInterval(maxDurationValue)
            } else if let maxDurationValue = settings["maxDuration"] as? TimeInterval {
                self.maxDuration = maxDurationValue
            }

            // Segment length is fixed at 1 minute for optimal performance

            // Calculate maximum segments to keep
            if let maxDuration = self.maxDuration {
                self.maxSegments = Int(ceil(maxDuration / self.segmentLength)) + 1
                self.log("Rolling window: maxDuration=\(maxDuration)s, segmentLength=\(self.segmentLength)s, maxSegments=\(self.maxSegments)")
            } else {
                self.maxSegments = AudioEngineConstants.defaultMaxSegments // Default maximum segments for memory management
                self.log("No maxDuration specified, using default maxSegments=\(self.maxSegments)")
            }

            self.log("Starting rolling segment recording with settings: \(settings)")
            self.startRecordingInternal(settings: settings, sampleRate: sampleRate, channels: channels, bitrate: bitrate)
        }
    }

    private func startRecordingInternal(settings: [String: Any], sampleRate: Int, channels: Int, bitrate: Int) {
        log("Starting rolling segment recording (maxDuration: \(maxDuration?.description ?? "none"), segmentLength: \(segmentLength)s - fixed)")

        do {
            // Configure audio session
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
            try session.setPreferredSampleRate(Double(sampleRate))
            try? session.setPreferredIOBufferDuration(0.02)
            try session.setActive(true)
            self.recordingSession = session

            // Set up interruption and route change handling
            self.setupAudioInterruptionHandling()

            // Initialize rolling recording state
            self.segmentURLs.removeAll()
            self.currentSegmentIndex = 0
            self.currentDuration = 0
            self.currentSegmentDuration = 0
            self.currentSegmentStartTime = Date()

            // Start first segment
            try self.startNewSegment(sampleRate: sampleRate, channels: channels, bitrate: bitrate)

            self.isRecording = true
            self.isPaused = false

            // Start duration monitoring
            self.startDurationMonitoring()

            // Start background pre-merge process
            self.startPreMergeProcess()

            // Notify delegate
            DispatchQueue.main.async {
                self.delegate?.recordingDidChangeState("recording", data: [
                    "duration": 0,
                    "isRecording": true,
                    "status": "recording"
                ])
            }

            log("Rolling segment recording started")
        } catch {
            log("Failed to start rolling segment recording: \(error.localizedDescription)")
            delegate?.recordingDidEncounterError(error)
        }
    }

    func pauseRecording() {
        performStateOperation {
            guard self.isRecording else {
                self.log("No active recording to pause")
                return
            }

            // Pause current segment
            self.currentSegmentWriter?.finishWriting { [weak self] in
                self?.log("Current segment finished writing during pause")
            }

            self.isRecording = false
            self.isPaused = true
            self.stopDurationMonitoring()

            // Notify delegate of state change to paused
            DispatchQueue.main.async {
                self.delegate?.recordingDidChangeState("paused", data: [
                    "duration": self.currentDuration,
                    "isRecording": false,
                    "status": "paused"
                ])
            }

            self.log("Rolling segment recording paused")
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
                // Resume with last settings
                let settings = self.lastRecordingSettings ?? [:]
                let sr = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
                let ch = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
                let br = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

                // Start new segment for resume
                self.currentSegmentStartTime = Date()
                try self.startNewSegment(sampleRate: sr, channels: ch, bitrate: br)

                self.isRecording = true
                self.isPaused = false
                self.startDurationMonitoring()

                // Notify delegate of state change to recording
                DispatchQueue.main.async {
                    self.delegate?.recordingDidChangeState("recording", data: [
                        "duration": self.currentDuration,
                        "isRecording": true,
                        "status": "recording"
                    ])
                }

                self.log("Rolling segment recording resumed")
            } catch {
                self.log("Failed to resume recording: \(error.localizedDescription)")
                self.delegate?.recordingDidEncounterError(error)
            }
        }
    }

    // MARK: - Reset Recording

    /// Reset the current recording session without finalizing a file
    /// Behavior:
    /// - Discards current recording data (segments)
    /// - Discards current duration and waves
    /// - Keeps last configured recording settings for seamless resume
    /// - Leaves the session in paused state so `resumeRecording()` can start fresh
    func resetRecording() {
        performStateOperation {
            // Guard against concurrent resets
            if self.isResetting {
                self.log("resetRecording ignored: already resetting")
                return
            }
            self.isResetting = true

            self.log("resetRecording called - isRecording=\(self.isRecording), isPaused=\(self.isPaused)")

            // Stop current segment if recording
            if self.isRecording {
                self.currentSegmentWriter?.finishWriting { [weak self] in
                    self?.log("Current segment finished writing during reset")
                }
            }

            // Stop duration monitoring and reset counters
            self.stopDurationMonitoring()
            self.currentDuration = 0
            self.lastReportedDuration = nil

            // Clean up segments
            self.cleanupSegments()

            // Cancel merge operations
            self.mergeTask?.cancel()
            self.mergeTask = nil

            // Reset segment state
            self.currentSegmentIndex = 0
            self.currentSegmentDuration = 0
            self.currentSegmentStartTime = nil

            // Set paused state
            self.isRecording = false
            self.isPaused = true

            // Notify delegate of paused state with duration 0
            DispatchQueue.main.async {
                self.delegate?.recordingDidChangeState("paused", data: [
                    "duration": 0,
                    "isRecording": false,
                    "status": "paused"
                ])
            }

            self.log("resetRecording completed: session is paused with duration reset to 0")

            // Clear resetting flag
            self.isResetting = false
        }
    }

    /**
     * Stops the current recording and returns the pre-merged file
     *
     * Performance Characteristics:
     * - Returns immediately (non-blocking) - always < 1 second
     * - Uses pre-merged file that's maintained in background
     * - No additional processing needed on stop
     *
     * Thread Safety: State changes are synchronized on state queue
     *
     * Delegate Callbacks:
     * - recordingDidFinish: Called when processing completes with file info
     * - recordingDidEncounterError: Called if stop/processing fails
     */
    func stopRecording() {
        performStateOperation {
            guard self.isRecording || self.isPaused else {
                self.log("No active recording to stop")
                return
            }

            // Stop monitoring immediately and set stopping state
            self.stopDurationMonitoring()
            self.isRecording = false
            self.isPaused = false

            self.log("Stopping rolling segment recording...")

            // Finish current segment if recording
            if let writer = self.currentSegmentWriter, writer.status == .writing {
                writer.finishWriting { [weak self] in
                    self?.log("Final segment finished writing")
                    self?.performSegmentOperation {
                        // Add final segment to list
                        if let url = self?.currentSegmentWriter?.outputURL {
                            self?.segmentURLs.append(url)
                        }
                    }
                }
            }

            // Cancel merge operations
            self.mergeTask?.cancel()
            self.mergeTask = nil

            self.log("Rolling segment recording stopped")

            // Return pre-merged file if available, otherwise create final merge
            if let preMergedURL = self.preMergedURL, FileManager.default.fileExists(atPath: preMergedURL.path) {
                self.log("Using pre-merged file: \(preMergedURL.lastPathComponent)")
                self.processRecordingFile(preMergedURL)
            } else {
                self.log("No pre-merged file available, creating final merge")
                self.createFinalMerge()
            }
        }
    }

    func getDuration() -> Int {
        log("getDuration: \(Int(currentDuration))s")
        return Int(currentDuration)
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

    // MARK: - Segment Management

    /**
     * Start a new recording segment
     */
    private func startNewSegment(sampleRate: Int, channels: Int, bitrate: Int) throws {
        // Create segment URL
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let segmentURL = documentsPath.appendingPathComponent("segment_\(currentSegmentIndex)_\(Int(Date().timeIntervalSince1970)).m4a")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: segmentURL.path) {
            try FileManager.default.removeItem(at: segmentURL)
        }

        // Create AVAssetWriter for segment
        let writer = try AVAssetWriter(outputURL: segmentURL, fileType: .m4a)

        // Configure audio input
        let audioInput = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: Double(sampleRate),
            AVNumberOfChannelsKey: channels,
            AVEncoderBitRateKey: bitrate,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ])

        audioInput.expectsMediaDataInRealTime = true

        guard writer.canAdd(audioInput) else {
            throw NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot add audio input to writer"])
        }

        writer.add(audioInput)

        guard writer.startWriting() else {
            throw NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Cannot start writing: \(writer.error?.localizedDescription ?? "Unknown error")"])
        }

        // Store current segment
        self.currentSegmentWriter = writer
        self.currentSegmentInput = audioInput
        self.currentSegmentDuration = 0
        self.currentSegmentStartTime = Date()

        log("Started new segment \(currentSegmentIndex) at \(segmentURL.lastPathComponent)")
    }

    /**
     * Finish current segment and start next one if needed
     */
    private func finishCurrentSegment() {
        guard let writer = currentSegmentWriter else { return }

        writer.finishWriting { [weak self] in
            guard let self = self else { return }

            self.performSegmentOperation {
                // Add completed segment to list
                self.segmentURLs.append(writer.outputURL)

                // Notify delegate of completed segment
                DispatchQueue.main.async {
                    self.delegate?.recordingDidCompleteSegment(self.currentSegmentIndex, duration: self.currentSegmentDuration)
                }

                self.log("Completed segment \(self.currentSegmentIndex) with duration \(self.currentSegmentDuration)s")

                // Check if we need to drop old segments
                self.manageRollingBuffer()

                // Increment segment index for next segment
                self.currentSegmentIndex += 1

                // Start next segment if still recording
                if self.isRecording {
                    do {
                        let settings = self.lastRecordingSettings ?? [:]
                        let sr = settings["sampleRate"] as? Int ?? Int(AudioEngineConstants.defaultSampleRate)
                        let ch = settings["channels"] as? Int ?? AudioEngineConstants.defaultChannels
                        let br = settings["bitrate"] as? Int ?? AudioEngineConstants.defaultBitrate

                        try self.startNewSegment(sampleRate: sr, channels: ch, bitrate: br)
                        self.log("Started next segment \(self.currentSegmentIndex)")
                    } catch {
                        self.log("Failed to start next segment: \(error.localizedDescription)")
                        DispatchQueue.main.async {
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }
                }
            }
        }
    }

    /**
     * Manage rolling buffer - drop old segments when maxSegments exceeded
     */
    private func manageRollingBuffer() {
        // Remove old segments if we exceed maxSegments
        while segmentURLs.count > maxSegments {
            if let oldSegmentURL = segmentURLs.first {
                try? FileManager.default.removeItem(at: oldSegmentURL)
                segmentURLs.removeFirst()
                log("Removed old segment: \(oldSegmentURL.lastPathComponent)")
            }
        }

        // Trigger pre-merge after adding new segment
        triggerPreMerge()
    }

    // MARK: - Pre-Merge Management

    /**
     * Start background pre-merge process
     */
    private func startPreMergeProcess() {
        // Initial merge after a short delay to allow first segment to complete
        DispatchQueue.main.asyncAfter(deadline: .now() + segmentLength + 1.0) { [weak self] in
            self?.triggerPreMerge()
        }
    }

    /**
     * Trigger pre-merge operation
     */
    private func triggerPreMerge() {
        mergeTask?.cancel()

        mergeTask = Task { [weak self] in
            await self?.performPreMerge()
        }
    }

    /**
     * Perform pre-merge of available segments
     */
    private func performPreMerge() async {
        guard !isMerging else {
            log("Merge already in progress, skipping")
            return
        }

        isMerging = true
        defer { isMerging = false }

        let segmentsToMerge = performSegmentOperation { Array(self.segmentURLs) }

        guard !segmentsToMerge.isEmpty else {
            log("No segments to merge")
            return
        }

        log("Starting pre-merge of \(segmentsToMerge.count) segments")

        do {
            let mergedURL = try await mergeSegments(segmentsToMerge)

            // Atomically replace pre-merged file
            performSegmentOperation {
                // Remove old pre-merged file
                if let oldURL = self.preMergedURL {
                    try? FileManager.default.removeItem(at: oldURL)
                }

                // Set new pre-merged file
                self.preMergedURL = mergedURL
                self.log("Pre-merge completed: \(mergedURL.lastPathComponent)")
            }
        } catch {
            log("Pre-merge failed: \(error.localizedDescription)")
        }
    }

    /**
     * Create final merge when stopping recording
     */
    private func createFinalMerge() {
        mergeTask = Task { [weak self] in
            guard let self = self else { return }

            let segmentsToMerge = await self.performSegmentOperation { Array(self.segmentURLs) }

            guard !segmentsToMerge.isEmpty else {
                await MainActor.run {
                    let error = NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "No segments to merge"])
                    self.delegate?.recordingDidEncounterError(error)
                }
                return
            }

            do {
                let mergedURL = try await self.mergeSegments(segmentsToMerge)
                await MainActor.run {
                    self.processRecordingFile(mergedURL)
                }
            } catch {
                await MainActor.run {
                    self.delegate?.recordingDidEncounterError(error)
                }
            }
        }
    }

    /**
     * Merge multiple audio segments into a single file
     */
    private func mergeSegments(_ segmentURLs: [URL]) async throws -> URL {
        guard !segmentURLs.isEmpty else {
            throw NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "No segments to merge"])
        }

        // If only one segment, return it directly
        if segmentURLs.count == 1 {
            return segmentURLs[0]
        }

        // Create composition
        let composition = AVMutableComposition()
        let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid)

        var currentTime = CMTime.zero

        // Add each segment to composition
        for segmentURL in segmentURLs {
            let asset = AVAsset(url: segmentURL)
            let timeRange = CMTimeRange(start: .zero, duration: asset.duration)

            do {
                try audioTrack?.insertTimeRange(timeRange, of: asset.tracks(withMediaType: .audio).first!, at: currentTime)
                currentTime = CMTimeAdd(currentTime, asset.duration)
            } catch {
                log("Failed to insert segment \(segmentURL.lastPathComponent): \(error.localizedDescription)")
                throw error
            }
        }

        // Export merged composition
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let mergedURL = documentsPath.appendingPathComponent("merged_\(Int(Date().timeIntervalSince1970)).m4a")

        // Remove existing file if it exists
        if FileManager.default.fileExists(atPath: mergedURL.path) {
            try FileManager.default.removeItem(at: mergedURL)
        }

        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            throw NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
        }

        exportSession.outputURL = mergedURL
        exportSession.outputFileType = .m4a

        // Wait for export completion
        let finalURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL, Error>) in
            exportSession.exportAsynchronously {
                if exportSession.status == .completed {
                    continuation.resume(returning: mergedURL)
                } else if exportSession.status == .failed {
                    continuation.resume(throwing: exportSession.error ?? NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Export failed"]))
                } else {
                    continuation.resume(throwing: NSError(domain: "RollingRecordingManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Export failed with status: \(exportSession.status)"]))
                }
            }
        }

        log("Successfully merged \(segmentURLs.count) segments into \(finalURL.lastPathComponent)")
        return finalURL
    }

    // MARK: - Duration Monitoring

    private func startDurationMonitoring() {
        stopDurationMonitoring()

        log("Starting duration monitoring for rolling segments")

        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        dispatchSource.schedule(deadline: .now(), repeating: .seconds(Int(AudioEngineConstants.timerInterval)))
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self else { return }

            if self.isResetting { return }

            // Update current segment duration
            if let startTime = self.currentSegmentStartTime {
                self.currentSegmentDuration = Date().timeIntervalSince(startTime)
            }

            // Update total duration
            let totalDuration = Double(self.currentSegmentIndex) * self.segmentLength + self.currentSegmentDuration
            self.currentDuration = totalDuration

            self.lastReportedDuration = totalDuration

            // Check if current segment should be finished
            if self.currentSegmentDuration >= self.segmentLength && self.isRecording {
                self.finishCurrentSegment()
            }

            DispatchQueue.main.async {
                let integerDuration = Int(totalDuration)
                self.delegate?.recordingDidUpdateDuration(integerDuration)
            }
        }

        self.durationDispatchSource = dispatchSource
        dispatchSource.resume()

        log("Duration monitoring started for rolling segments")
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

        Task {
            do {
                // Get basic file info
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileToReturn.path)
                let fileSize = fileAttributes[.size] as? Int64 ?? 0
                let modificationDate = fileAttributes[.modificationDate] as? Date ?? Date()

                log("File attributes - size: \(fileSize), modified: \(modificationDate)")

                // Calculate duration asynchronously
                let asset = AVAsset(url: fileToReturn)
                let durationInSeconds = await asset.loadDurationAsync()

                log("Recording duration calculated: \(durationInSeconds) seconds")

                // Create response with actual duration
                let response = createRecordingResponseWithDuration(
                    fileToReturn: fileToReturn,
                    fileSize: fileSize,
                    modificationDate: modificationDate,
                    durationInSeconds: durationInSeconds
                )

                log("Created response with duration: \(durationInSeconds)s")

                // Return to main actor for delegate callback
                await MainActor.run {
                    log("Calling delegate recordingDidFinish")
                    self.delegate?.recordingDidFinish(response)
                }

            } catch {
                log("Error in processRecordingFile: \(error.localizedDescription)")
                await MainActor.run {
                    self.delegate?.recordingDidEncounterError(error as NSError)
                }
            }
        }
    }

    private func createRecordingResponseWithDuration(
        fileToReturn: URL,
        fileSize: Int64,
        modificationDate: Date,
        durationInSeconds: Double
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
            "filename": fileToReturn.lastPathComponent
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

        // Cancel any running tasks
        mergeTask?.cancel()
        mergeTask = nil

        // Stop duration monitoring
        stopDurationMonitoring()

        // Clean up segments
        cleanupSegments()

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

    private func cleanupSegments() {
        // Clean up segment files
        for segmentURL in segmentURLs {
            try? FileManager.default.removeItem(at: segmentURL)
        }
        segmentURLs.removeAll()

        // Clean up pre-merged file
        if let preMergedURL = preMergedURL {
            try? FileManager.default.removeItem(at: preMergedURL)
            self.preMergedURL = nil
        }

        // Clean up current segment
        if let currentURL = currentSegmentWriter?.outputURL {
            try? FileManager.default.removeItem(at: currentURL)
        }
        currentSegmentWriter = nil
        currentSegmentInput = nil
    }

    // MARK: - Audio Interruption Handling

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

    private func removeAudioInterruptionHandling() {
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.interruptionNotification, object: nil)
        NotificationCenter.default.removeObserver(self, name: AVAudioSession.routeChangeNotification, object: nil)
        log("Audio interruption handling removed")
    }

    @objc private func handleAudioSessionInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        log("Audio session interruption: \(type == .began ? "began" : "ended")")

        switch type {
        case .began:
            // Pause recording for interruptions
            if isRecording {
                pauseRecording()
            }

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    // Reactivate audio session
                    do {
                        try AVAudioSession.sharedInstance().setActive(true)

                        // Resume recording if it was paused
                        if !isRecording && isPaused {
                            resumeRecording()
                        }
                    } catch {
                        log("Failed to resume recording after interruption: \(error.localizedDescription)")
                        delegate?.recordingDidEncounterError(error)
                    }
                }
            }

        @unknown default:
            log("Unknown audio session interruption type: \(typeValue)")
        }
    }

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

        case .newDeviceAvailable:
            log("New audio device available (e.g., headphones connected)")

        default:
            log("Other audio route change: \(reason.rawValue)")
        }
    }

    // MARK: - Utility Methods

    private func log(_ message: String) {
        #if DEBUG
        print("[RollingRecordingManager] \(message)")
        #endif
    }
}
