import Foundation
@preconcurrency import AVFoundation

/**
 * Manages segment rolling audio recording with automatic cleanup
 * Features:
 * - Linear recording without segments when no maxDuration is set
 * - Records audio in 30-second segments when maxDuration is set
 * - Maintains a rolling buffer based on maxDuration (keeps only last N seconds)
 * - Duration reporting continues to count up (never resets)
 * - Final recording contains only the last maxDuration seconds of audio
 * - Automatically removes oldest segments when buffer reaches capacity
 * - Merges segments into final audio file when recording stops
 */
class SegmentRollingManager: NSObject {

    // MARK: - Properties

    private var segmentBuffer: [URL] = []
    private var segmentTimer: Timer?
    private var currentSegmentRecorder: AVAudioRecorder?
    private var recordingSession: AVAudioSession?
    private var segmentCounter: Int = 0
    private var isActive: Bool = false
    private let segmentsDirectory: URL

    // Recording settings
    private var recordingSettings: [String: Any] = [:]

    // Duration control
    private var maxDuration: TimeInterval?

    // Recording mode - determines if we use segments or linear recording
    private var useSegmentRolling: Bool = false

    // Total recording duration tracking (never resets)
    private var totalRecordingDuration: TimeInterval = 0
    private var recordingStartTime: Date?    // Thread safety
    private let queue = DispatchQueue(label: "segment-rolling-queue", qos: .userInteractive)
    private let queueKey = DispatchSpecificKey<String>()
    private let queueValue = "segment-rolling-queue"

    // MARK: - Initialization

    override init() {
        #if DEBUG
        print("[SegmentRollingManager] SegmentRollingManager.init() - Starting initialization")
        #endif

        // Initialize segmentsDirectory first
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
        // Avoid nested sync calls by checking if we're already on the queue
        if DispatchQueue.getSpecific(key: queueKey) == queueValue {
            try startSegmentRollingInternal(with: settings)
        } else {
            return try queue.sync {
                try self.startSegmentRollingInternal(with: settings)
            }
        }
    }

    private func startSegmentRollingInternal(with settings: [String: Any]) throws {
        log("Inside queue.sync block")
        guard !isActive else {
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Segment rolling already active"])
        }

        // Store recording settings
        recordingSettings = settings
        log("Recording settings stored")

        // Configure audio session
        log("Getting audio session instance")
        let audioSession = AVAudioSession.sharedInstance()
        log("Setting audio session category")
        try audioSession.setCategory(.playAndRecord, mode: .measurement, options: [.allowBluetooth, .defaultToSpeaker])
        log("Activating audio session")
        try audioSession.setActive(true)
        recordingSession = audioSession
        log("Audio session configured successfully")

        // Reset state
        segmentCounter = 0
        segmentBuffer.removeAll()
        isActive = true
        totalRecordingDuration = 0
        recordingStartTime = Date()

        // Determine recording mode based on maxDuration
        useSegmentRolling = maxDuration != nil

        log("Starting recording with mode: \(useSegmentRolling ? "segment rolling" : "linear")")

        // Start recording
        log("About to call startNewSegment()")
        try startNewSegment()
        log("startNewSegment() completed successfully")

        // Only start segment timer if using segment rolling
        if useSegmentRolling {
            DispatchQueue.main.async {
                self.segmentTimer = Timer.scheduledTimer(withTimeInterval: AudioEngineConstants.segmentDuration, repeats: true) { _ in
                    self.rotateSegment()
                }
            }
            log("Started segment rolling with 30-second intervals")
        } else {
            log("Started linear recording (no segments)")
        }
    }

    /**
     * Pause segment rolling recording
     */
    func pauseSegmentRolling() {
        queue.sync {
            guard isActive else { return }

            currentSegmentRecorder?.pause()

            // Only stop timer if using segment rolling
            if useSegmentRolling {
                segmentTimer?.invalidate()
                segmentTimer = nil
            }

            log("Paused \(useSegmentRolling ? "segment rolling" : "linear") recording")
        }
    }

    /**
     * Resume segment rolling recording
     */
    func resumeSegmentRolling() throws {
        return try queue.sync {
            guard isActive else {
                throw NSError(domain: "SegmentRollingManager", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "No segment rolling to resume"])
            }

            // Resume current segment or start new one if needed
            if let recorder = currentSegmentRecorder {
                if !recorder.record() {
                    // If can't resume, start new segment
                    try startNewSegment()
                }
            } else {
                try startNewSegment()
            }

            // Only restart segment timer if using segment rolling
            if useSegmentRolling {
                DispatchQueue.main.async {
                    self.segmentTimer = Timer.scheduledTimer(withTimeInterval: AudioEngineConstants.segmentDuration, repeats: true) { _ in
                        self.rotateSegment()
                    }
                }
            }

            log("Resumed \(useSegmentRolling ? "segment rolling" : "linear") recording")
        }
    }

    /**
     * Stop segment rolling and merge all segments into final file
     * - returns: URL of the merged audio file
     */
    func stopSegmentRolling() throws -> URL {
        return try queue.sync {
            guard isActive else {
                throw NSError(domain: "SegmentRollingManager", code: -1,
                            userInfo: [NSLocalizedDescriptionKey: "No segment rolling to stop"])
            }

            // Stop timer if using segment rolling
            if useSegmentRolling {
                DispatchQueue.main.sync {
                    segmentTimer?.invalidate()
                    segmentTimer = nil
                }
            }

            // Stop current segment recording
            currentSegmentRecorder?.stop()
            currentSegmentRecorder = nil

            let finalFileURL: URL

            if useSegmentRolling {
                // Segment rolling mode - add current segment to buffer and merge
                log("Stopping segment rolling mode")

                // Add current segment to buffer if it exists and has content
                if let lastSegmentURL = getLastSegmentURL() {
                    log("Found last segment, adding to buffer")
                    addSegmentToBuffer(lastSegmentURL)
                } else {
                    log("No last segment found")
                }

                // Check if we have any segments to merge
                if segmentBuffer.isEmpty {
                    log("Warning: No segments in buffer, this might be a very short recording")
                    throw NSError(domain: "SegmentRollingManager", code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "No valid recording segments found - recording may have been too short"])
                }

                // Merge all segments
                let mergedFileURL = try mergeSegments()

                // Apply trimming if maxDuration is set (matching Android behavior)
                if let maxDuration = maxDuration {
                    log("Applying trimming to merged file with maxDuration: \(maxDuration) seconds")
                    finalFileURL = try trimMergedFile(mergedFileURL, maxDuration: maxDuration)
                    log("Successfully trimmed recording to exact maxDuration")
                } else {
                    finalFileURL = mergedFileURL
                }

                log("Stopped segment rolling and merged \(segmentBuffer.count) segments")
            } else {
                // Linear recording mode - return the single recorded file
                log("Stopping linear recording mode")

                guard let lastSegmentURL = getLastSegmentURL(),
                      FileManager.default.fileExists(atPath: lastSegmentURL.path) else {
                    throw NSError(domain: "SegmentRollingManager", code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "No recording file found"])
                }

                // Move the single file to final location
                let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let tempFinalFileURL = documentsPath.appendingPathComponent("linear_recording_\(Int(Date().timeIntervalSince1970)).m4a")

                try FileManager.default.moveItem(at: lastSegmentURL, to: tempFinalFileURL)

                // Apply trimming if maxDuration is set (matching Android behavior)
                if let maxDuration = maxDuration {
                    log("Applying trimming to linear recording with maxDuration: \(maxDuration) seconds")
                    finalFileURL = try trimMergedFile(tempFinalFileURL, maxDuration: maxDuration)
                    log("Successfully trimmed linear recording to exact maxDuration")
                } else {
                    finalFileURL = tempFinalFileURL
                }

                log("Stopped linear recording")
            }

            // Cleanup
            isActive = false
            let wasUsingSegmentRolling = useSegmentRolling
            useSegmentRolling = false
            totalRecordingDuration = 0
            recordingStartTime = nil
            if wasUsingSegmentRolling {
                cleanupSegments()
            }

            return finalFileURL
        }
    }

    /**
     * Get current recording duration across all segments
     * For segment rolling: Returns total elapsed time since recording started (never resets)
     * For linear recording: Returns current recorder time
     */
    func getCurrentDuration() -> TimeInterval {
        return queue.sync {
            guard isActive else { return 0 }

            if useSegmentRolling {
                // For segment rolling, return total elapsed time since recording started
                if let startTime = recordingStartTime {
                    return Date().timeIntervalSince(startTime)
                } else {
                    // Fallback to old calculation if startTime is somehow nil
                    let segmentsDuration = Double(segmentBuffer.count) * AudioEngineConstants.segmentDuration
                    let currentSegmentDuration = currentSegmentRecorder?.currentTime ?? 0
                    return segmentsDuration + currentSegmentDuration
                }
            } else {
                // Linear recording - just return current time
                return currentSegmentRecorder?.currentTime ?? 0
            }
        }
    }

    /**
     * Get the duration of audio currently buffered (available for processing)
     * This represents the actual audio that will be included in the final recording
     */
    func getBufferedDuration() -> TimeInterval {
        return queue.sync {
            guard isActive && useSegmentRolling else { return getCurrentDuration() }

            let segmentsDuration = Double(segmentBuffer.count) * AudioEngineConstants.segmentDuration
            let currentSegmentDuration = currentSegmentRecorder?.currentTime ?? 0
            return segmentsDuration + currentSegmentDuration
        }
    }

    /**
     * Check if segment rolling is currently active
     */
    func isSegmentRollingActive() -> Bool {
        return queue.sync { isActive }
    }

    /**
     * Set maximum recording duration in seconds
     * Setting maxDuration enables segment rolling mode
     * Not setting maxDuration (nil) enables linear recording mode
     */
    func setMaxDuration(_ duration: TimeInterval?) {
        queue.sync {
            maxDuration = duration
            if let duration = duration {
                log("Set max duration to \(duration) seconds - segment rolling mode will be used")
            } else {
                log("Max duration cleared - linear recording mode will be used")
            }
        }
    }

    /**
     * Set maximum recording duration in seconds (backwards compatibility)
     */
    func setMaxDuration(_ duration: TimeInterval) {
        setMaxDuration(duration as TimeInterval?)
    }

    // MARK: - Private Methods

    /**
     * Start recording a new segment
     */
        private func startNewSegment() throws {
        log("startNewSegment() - Getting segment URL for counter: \(segmentCounter)")
        let segmentURL = getSegmentURL(for: segmentCounter)
        log("startNewSegment() - Segment URL: \(segmentURL.path)")

        log("startNewSegment() - Creating AVAudioRecorder with settings: \(recordingSettings)")
        do {
            currentSegmentRecorder = try AVAudioRecorder(url: segmentURL, settings: recordingSettings)
            log("startNewSegment() - AVAudioRecorder created successfully")
        } catch {
            log("startNewSegment() - Failed to create AVAudioRecorder: \(error.localizedDescription)")
            throw error
        }

        currentSegmentRecorder?.delegate = self
        log("startNewSegment() - Delegate set")

        guard let recorder = currentSegmentRecorder else {
            log("startNewSegment() - Current segment recorder is nil after creation")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Current segment recorder is nil"])
        }

        log("startNewSegment() - Starting recording on AVAudioRecorder")
        if !recorder.record() {
            log("startNewSegment() - recorder.record() returned false")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to start segment recording"])
        }

        log("Started segment \(segmentCounter)")
    }

    /**
     * Rotate to next segment - called by timer (only in segment rolling mode)
     */
    private func rotateSegment() {
        queue.async {
            guard self.isActive && self.useSegmentRolling else { return }

            let totalElapsed = self.recordingStartTime.map { Date().timeIntervalSince($0) } ?? 0
            self.log("Rotating segment \(self.segmentCounter) after 30s (total elapsed: \(totalElapsed)s)")

            // Stop current segment
            if let recorder = self.currentSegmentRecorder {
                recorder.stop()

                // Add completed segment to buffer
                let segmentURL = self.getSegmentURL(for: self.segmentCounter)
                self.addSegmentToBuffer(segmentURL)
            }

            // Move to next segment
            self.segmentCounter += 1

            // Start new segment
            do {
                try self.startNewSegment()
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
        // Only add to buffer in segment rolling mode
        guard useSegmentRolling else { return }

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
            // Fallback to default (should not happen in segment rolling mode)
            effectiveMaxSegments = AudioEngineConstants.maxSegments
        }

        // Remove oldest segments if over limit
        while segmentBuffer.count > effectiveMaxSegments {
            let oldestSegment = segmentBuffer.removeFirst()

            // Delete old segment file
            do {
                try FileManager.default.removeItem(at: oldestSegment)
                log("Removed old segment: \(oldestSegment.lastPathComponent) - maintaining rolling window of \(effectiveMaxSegments) segments")
            } catch {
                log("Failed to remove old segment: \(error.localizedDescription)")
            }
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
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "No segments to merge"])
        }

        // Create output file
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let outputURL = documentsPath.appendingPathComponent("merged_recording_\(Int(Date().timeIntervalSince1970)).m4a")
        log("Output URL: \(outputURL.path)")

        // Use AVAssetExportSession for high-quality merging
        let composition = AVMutableComposition()

        guard let audioTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            log("Error: Failed to create audio track for composition")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to create audio track"])
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
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "No valid segments to merge"])
        }

        log("Added \(successfulSegments) segments to composition, starting export...")

        // Export merged composition
        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            log("Error: Failed to create export session")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        // Use semaphore to wait for export completion
        let semaphore = DispatchSemaphore(value: 0)
        var exportError: Error?

        log("Starting export session...")
        exportSession.exportAsynchronously { @Sendable in
            if exportSession.status == .failed {
                exportError = exportSession.error
            }
            semaphore.signal()
        }

        semaphore.wait()
        log("Export session completed with status: \(exportSession.status)")

        if let error = exportError {
            log("Export error: \(error.localizedDescription)")
            throw error
        }

        if exportSession.status != .completed {
            log("Export failed with status: \(exportSession.status)")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Export failed with status: \(exportSession.status)"])
        }

        log("Successfully merged \(segmentBuffer.count) segments into: \(outputURL.lastPathComponent)")

        return outputURL
    }

    /**
     * Trim merged file to specified duration
     */
    private func trimMergedFile(_ sourceURL: URL, maxDuration: TimeInterval) throws -> URL {
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
            throw NSError(domain: "SegmentRollingManager", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Failed to create export session for trimming"])
        }

        exportSession.outputURL = trimmedURL
        exportSession.outputFileType = .m4a
        exportSession.timeRange = timeRange

        // Use semaphore to wait for export completion
        let semaphore = DispatchSemaphore(value: 0)
        var exportError: Error?

        log("Starting trim export...")
        exportSession.exportAsynchronously { @Sendable in
            if exportSession.status == .failed {
                exportError = exportSession.error
            }
            semaphore.signal()
        }

        semaphore.wait()
        log("Trim export completed with status: \(exportSession.status)")

        if let error = exportError {
            log("Trim export error: \(error.localizedDescription)")
            throw error
        }

        if exportSession.status != .completed {
            log("Trim export failed with status: \(exportSession.status)")
            throw NSError(domain: "SegmentRollingManager", code: -1,
                         userInfo: [NSLocalizedDescriptionKey: "Audio trimming failed with status: \(exportSession.status)"])
        }

        log("Successfully trimmed merged file from \(duration)s to \(maxDuration)s (last \(maxDuration) seconds)")

        // Clean up original merged file
        try FileManager.default.removeItem(at: sourceURL)
        log("Cleaned up original merged file")

        return trimmedURL
    }

    /**
     * Get URL for segment file
     */
    private func getSegmentURL(for index: Int) -> URL {
        if useSegmentRolling {
            return segmentsDirectory.appendingPathComponent("segment_\(index).m4a")
        } else {
            // Linear recording uses a single file
            return segmentsDirectory.appendingPathComponent("linear_recording.m4a")
        }
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
     * Clean up all segment files
     */
    private func cleanupSegments() {
        for segmentURL in segmentBuffer {
            do {
                try FileManager.default.removeItem(at: segmentURL)
            } catch {
                log("Failed to cleanup segment: \(error.localizedDescription)")
            }
        }
        segmentBuffer.removeAll()
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

    // MARK: - Cleanup

    deinit {
        cleanup()
    }

    /// Comprehensive cleanup of all segment rolling resources
    func cleanup() {
        queue.sync {
            log("Starting SegmentRollingManager cleanup")

            // Stop timer
            segmentTimer?.invalidate()
            segmentTimer = nil

            // Stop current recorder
            if let recorder = currentSegmentRecorder {
                if recorder.isRecording {
                    recorder.stop()
                }
                recorder.delegate = nil
                currentSegmentRecorder = nil
            }

            // Clean up temporary segment files
            cleanupSegments()

            // Deactivate audio session
            if let session = recordingSession {
                do {
                    try session.setActive(false, options: .notifyOthersOnDeactivation)
                    recordingSession = nil
                    log("SegmentRollingManager audio session deactivated")
                } catch {
                    log("Warning: Failed to deactivate segment rolling audio session: \(error.localizedDescription)")
                }
            }

            // Reset state
            isActive = false
            segmentCounter = 0
            totalRecordingDuration = 0
            recordingStartTime = nil
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
