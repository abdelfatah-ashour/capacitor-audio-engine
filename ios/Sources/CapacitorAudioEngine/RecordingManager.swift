import Foundation
import AVFoundation
import UIKit

protocol RecordingManagerDelegate: AnyObject {
    func recordingDidChangeStatus(_ status: String, reason: String, message: String?, recoverable: Bool?)
    func recordingDidEncounterError(_ error: Error)
    func recordingDidUpdateDuration(_ duration: Double)
    func recordingDidEmitWaveLevel(_ level: Double, timestamp: TimeInterval)
    func recordingDidFinalize(_ path: String)
}

final class RecordingManager {
    private let stateQueue = DispatchQueue(label: "audio-engine-recording-state", qos: .userInitiated)

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    private weak var delegate: RecordingManagerDelegate?
    private var audioEngine: AVAudioEngine?
    private var inputNodeTapInstalled: Bool = false
    private var isRecording: Bool = false
    private var isPaused: Bool = false
    private var currentSampleRate: Double = 44100
    private var currentChannels: AVAudioChannelCount = 1

    // Duration monitoring
    private var durationTimer: Timer?
    private var currentDuration: Double = 0.0
    private var isDurationMonitoring: Bool = false
    private var isDurationPaused: Bool = false

    // Wave level monitoring
    private var waveLevelTimer: Timer?
    private var isWaveLevelMonitoring: Bool = false
    private var isWaveLevelPaused: Bool = false
    private var waveLevelEmissionInterval: TimeInterval = 1.0 // Default 1 second

    // Encoding
    private var desiredBitrate: Int = 128_000
    private var aacFormat: AVAudioFormat?
    private var converter: AVAudioConverter?

    // Asset Writer (native .m4a) — one writer per segment.
    private var assetWriter: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var fileURL: URL?
    private var writerStarted: Bool = false
    private var audioSessionObserver: Any?
    private var routeChangeObserver: Any?

    // Segment-based recording state. `segmentURLs` holds each finalized
    // segment in capture order; on stop they are concatenated into
    // `finalOutputURL`.
    private var segmentURLs: [URL] = []
    private var segmentDirectory: URL?
    private var segmentIndex: Int = 0
    private var finalOutputURL: URL?
    private var previewURL: URL?

    // App lifecycle observers
    private var appWillEnterForegroundObserver: Any?
    private var appDidBecomeActiveObserver: Any?
    private var mediaServicesResetObserver: Any?

    // Health check timer
    private var healthCheckTimer: Timer?

    init(delegate: RecordingManagerDelegate?) {
        self.delegate = delegate
    }

    func configureRecording(encoding: String?, bitrate: Int?, path: String? = nil) {
        if let br = bitrate, br > 0 { desiredBitrate = br }

        let resolvedFinalURL: URL
        if let p = path, !p.isEmpty {
            // Normalize provided path into app sandbox
            if p.hasPrefix("file://") {
                if let url = URL(string: p) {
                    resolvedFinalURL = url
                } else {
                    let pathString = String(p.dropFirst(7))
                    resolvedFinalURL = URL(fileURLWithPath: pathString)
                }
            } else if p.hasPrefix("/") {
                let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
                let trimmed = String(p.dropFirst())
                resolvedFinalURL = base.appendingPathComponent(trimmed)
            } else {
                let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
                resolvedFinalURL = base.appendingPathComponent(p)
            }
        } else {
            // Use same directory as Capacitor Filesystem Directory.Data
            // On iOS, this maps to the app's data directory (Library/Application Support)
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
            resolvedFinalURL = base.appendingPathComponent("recording_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        }

        finalOutputURL = resolvedFinalURL

        // Ensure directory exists for the output file
        let dir = resolvedFinalURL.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: dir.path) {
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            } catch {
                delegate?.recordingDidEncounterError(NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create output directory: \(error.localizedDescription)"]))
            }
        }
    }

    func getFormatInfo() -> (sampleRate: Int, channels: Int, encoding: String, mimeType: String, bitrate: Int, path: String?) {
        return (Int(currentSampleRate), Int(currentChannels), "aac", "audio/aac", desiredBitrate, finalOutputURL?.path)
    }

    // MARK: - Segment helpers

    private func ensureSegmentDirectory() -> URL {
        if let dir = segmentDirectory { return dir }
        let parentDir: URL
        if let finalURL = finalOutputURL {
            parentDir = finalURL.deletingLastPathComponent()
        } else {
            parentDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
        }
        let dir = parentDir.appendingPathComponent(".audio_engine_segments_\(Int(Date().timeIntervalSince1970 * 1000))")
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        }
        segmentDirectory = dir
        return dir
    }

    private func nextSegmentURL() -> URL {
        let dir = ensureSegmentDirectory()
        let name = String(format: "segment_%03d.m4a", segmentIndex)
        segmentIndex += 1
        return dir.appendingPathComponent(name)
    }

    private func cleanupSegments() {
        for url in segmentURLs {
            try? FileManager.default.removeItem(at: url)
        }
        segmentURLs.removeAll()

        if let url = fileURL, FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.removeItem(at: url)
        }
        fileURL = nil

        if let dir = segmentDirectory {
            // Best-effort: remove any leftover files plus the dir itself.
            if let leftovers = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
                for url in leftovers {
                    try? FileManager.default.removeItem(at: url)
                }
            }
            try? FileManager.default.removeItem(at: dir)
        }
        segmentDirectory = nil
        segmentIndex = 0
    }

    func startRecording() {
        performStateOperation {
            guard !isRecording else { return }
            do {
                // Drop any leftover segments / preview from a prior session.
                cleanupSegments()
                cleanupPausedPlaybackPreview()

                try configureAudioSessionForRecording()
                observeAudioSessionInterruptions()
                observeAudioSessionRouteChanges()
                observeAppLifecycle()
                let engine = AVAudioEngine()
                audioEngine = engine

                let input = engine.inputNode
                let inputFormat = input.outputFormat(forBus: 0)
                currentSampleRate = inputFormat.sampleRate
                currentChannels = inputFormat.channelCount

                // Prepare AAC output format and converter for JS emission
                let settings: [String: Any] = [
                    AVFormatIDKey: kAudioFormatMPEG4AAC,
                    AVNumberOfChannelsKey: Int(currentChannels),
                    AVSampleRateKey: currentSampleRate,
                    AVEncoderBitRateKey: desiredBitrate
                ]
                guard let outFormat = AVAudioFormat(settings: settings) else {
                    throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create AAC format"])
                }
                aacFormat = outFormat
                converter = AVAudioConverter(from: inputFormat, to: outFormat)

                // Allocate the first segment file and point the writer at it.
                fileURL = nextSegmentURL()
                try setupAssetWriter(inputFormat: inputFormat)

                if !inputNodeTapInstalled {
                    input.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { [weak self] buffer, time in
                        guard let self = self else { return }
                        // Respect pause without finalizing writer; skip processing while paused
                        let shouldSkip: Bool = self.performStateOperation { self.isPaused || !self.isRecording }
                        if shouldSkip { return }

                        // Start writer session at first buffer
                        if let writer = self.assetWriter, writer.status == .writing, !self.writerStarted {
                            self.writerStarted = true
                            let startTime = CMTime(seconds: Double(time.sampleTime) / buffer.format.sampleRate, preferredTimescale: 1_000_000_000)
                            writer.startSession(atSourceTime: startTime)
                        }
                        // Append PCM to writer (it will encode to AAC)
                        self.appendToWriter(buffer: buffer, at: time)
                        // Emit AAC to JS
                        self.encodeAndEmit(buffer: buffer)
                    }
                    inputNodeTapInstalled = true
                }

                try engine.start()
                isRecording = true
                isPaused = false

                // Start duration and wave level monitoring
                startDurationMonitoring()
                startWaveLevelMonitoring()
                startRecordingHealthCheck()

                delegate?.recordingDidChangeStatus("recording", reason: "user", message: nil, recoverable: nil)
            } catch {
                delegate?.recordingDidEncounterError(error)
            }
        }
    }

    func stopRecording() {
        stopRecordingAndWaitForFile { _ in }
    }

    /// Stop recording, finalize the active segment, concatenate every segment
    /// captured during the session into the user-requested final output path,
    /// and report that path via the completion handler.
    func stopRecordingAndWaitForFile(completion: @escaping (String?) -> Void) {
        let shouldProceed: Bool = performStateOperation {
            guard isRecording else { return false }
            return true
        }

        if !shouldProceed {
            completion(finalOutputURL?.path)
            return
        }

        performStateOperation {
            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.stop()
            }
            removeAudioSessionInterruptionsObserver()
            removeAudioSessionRouteChangeObserver()
            removeAppLifecycleObservers()

            stopDurationMonitoring()
            stopWaveLevelMonitoring()
            stopRecordingHealthCheck()
        }

        finalizeActiveSegment { [weak self] in
            guard let self = self else {
                completion(nil)
                return
            }
            self.assembleFinalOutput { resultPath in
                self.performStateOperation {
                    self.audioEngine = nil
                    self.converter = nil
                    self.aacFormat = nil

                    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)

                    self.cleanupSegments()
                    self.cleanupPausedPlaybackPreview()

                    self.isRecording = false
                    self.isPaused = false
                    self.delegate?.recordingDidChangeStatus("stopped", reason: "user", message: nil, recoverable: nil)
                    completion(resultPath ?? self.finalOutputURL?.path)
                }
            }
        }
    }

    /// Pause recording and finalize the in-flight segment to disk so it can
    /// be played back / concatenated. The completion handler fires once the
    /// segment file is fully written.
    func pauseRecording(completion: (() -> Void)? = nil) {
        let shouldProceed: Bool = performStateOperation {
            guard isRecording && !isPaused else { return false }
            isPaused = true
            pauseDurationMonitoring()
            pauseWaveLevelMonitoring()
            return true
        }

        guard shouldProceed else {
            completion?()
            return
        }

        finalizeActiveSegment { [weak self] in
            self?.delegate?.recordingDidChangeStatus("paused", reason: "user", message: nil, recoverable: nil)
            completion?()
        }
    }

    func resumeRecording() {
        performStateOperation {
            guard isRecording && isPaused else { return }

            // Drop any preview that was created for paused playback before we
            // start capturing more audio.
            cleanupPausedPlaybackPreview()

            do {
                guard let engine = audioEngine else {
                    throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Audio engine not initialized"])
                }

                let input = engine.inputNode
                let inputFormat = input.outputFormat(forBus: 0)

                // Allocate and prepare a writer for the next segment.
                fileURL = nextSegmentURL()
                try setupAssetWriter(inputFormat: inputFormat)

                if !inputNodeTapInstalled {
                    input.installTap(onBus: 0, bufferSize: 2048, format: inputFormat) { [weak self] buffer, time in
                        guard let self = self else { return }
                        let shouldSkip: Bool = self.performStateOperation { self.isPaused || !self.isRecording }
                        if shouldSkip { return }

                        if let writer = self.assetWriter, writer.status == .writing, !self.writerStarted {
                            self.writerStarted = true
                            let startTime = CMTime(seconds: Double(time.sampleTime) / buffer.format.sampleRate, preferredTimescale: 1_000_000_000)
                            writer.startSession(atSourceTime: startTime)
                        }
                        self.appendToWriter(buffer: buffer, at: time)
                        self.encodeAndEmit(buffer: buffer)
                    }
                    inputNodeTapInstalled = true
                }

                if !engine.isRunning {
                    try engine.start()
                }
            } catch {
                delegate?.recordingDidEncounterError(error)
                return
            }

            isPaused = false
            resumeDurationMonitoring()
            resumeWaveLevelMonitoring()

            delegate?.recordingDidChangeStatus("recording", reason: "user", message: nil, recoverable: nil)
        }
    }

    /// Reset the current recording session without finalizing a file.
    /// Behavior:
    /// - Keeps audio engine configured but removes active taps
    /// - Discards every captured segment
    /// - Resets duration and wave level monitoring counters to 0
    /// - Leaves the recording in paused state so `resumeRecording()` can continue fresh
    func resetRecording(completion: (() -> Void)? = nil) {
        let shouldProceed: Bool = performStateOperation {
            guard isRecording else { return false }

            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.pause()
            }

            pauseDurationMonitoring()
            pauseWaveLevelMonitoring()
            currentDuration = 0.0
            isPaused = true
            return true
        }

        guard shouldProceed else {
            completion?()
            return
        }

        // Finish whatever writer is currently open, then discard everything.
        let finalize: () -> Void = { [weak self] in
            guard let self = self else {
                completion?()
                return
            }
            self.performStateOperation {
                self.assetWriter = nil
                self.writerInput = nil
                self.writerStarted = false
                self.cleanupSegments()
                self.cleanupPausedPlaybackPreview()
            }
            self.delegate?.recordingDidChangeStatus("paused", reason: "user", message: nil, recoverable: nil)
            completion?()
        }

        if assetWriter != nil {
            finishWriterAndWait {
                finalize()
            }
        } else {
            finalize()
        }
    }

    // MARK: - Segment finalization

    /// Closes the current asset writer (if any) and registers its file as a
    /// finalized segment. Safe to call repeatedly. The completion handler
    /// fires once the file is on disk.
    private func finalizeActiveSegment(completion: @escaping () -> Void) {
        guard assetWriter != nil else {
            completion()
            return
        }

        let segmentURL = fileURL
        finishWriterAndWait { [weak self] in
            guard let self = self else {
                completion()
                return
            }
            self.performStateOperation {
                if let url = segmentURL,
                   FileManager.default.fileExists(atPath: url.path),
                   let attributes = try? FileManager.default.attributesOfItem(atPath: url.path),
                   let size = attributes[.size] as? Int64,
                   size > 0 {
                    self.segmentURLs.append(url)
                } else if let url = segmentURL {
                    print("[RecordingManager] Discarding empty segment: \(url.path)")
                    try? FileManager.default.removeItem(at: url)
                }
                self.fileURL = nil
                self.assetWriter = nil
                self.writerInput = nil
                self.writerStarted = false
            }
            completion()
        }
    }

    /// Concatenate all captured segments into `finalOutputURL`. If only one
    /// segment exists it is moved into place. Reports the final output path
    /// via the completion handler.
    private func assembleFinalOutput(completion: @escaping (String?) -> Void) {
        guard let outputURL = finalOutputURL else {
            completion(nil)
            return
        }
        let segmentsCopy = segmentURLs

        if segmentsCopy.isEmpty {
            print("[RecordingManager] No segments captured for stopRecording")
            completion(nil)
            return
        }

        // Make sure the destination directory exists and that we don't collide
        // with a stale file from a previous run.
        let parent = outputURL.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: parent.path) {
            try? FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        }
        if FileManager.default.fileExists(atPath: outputURL.path) {
            try? FileManager.default.removeItem(at: outputURL)
        }

        if segmentsCopy.count == 1 {
            do {
                try FileManager.default.moveItem(at: segmentsCopy[0], to: outputURL)
                delegate?.recordingDidFinalize(outputURL.path)
                completion(outputURL.path)
            } catch {
                print("[RecordingManager] Failed to move single segment into place: \(error.localizedDescription)")
                self.delegate?.recordingDidEncounterError(error)
                completion(nil)
            }
            return
        }

        concatenateSegments(segmentsCopy, to: outputURL) { [weak self] error in
            if let error = error {
                print("[RecordingManager] Failed to concatenate segments: \(error.localizedDescription)")
                self?.delegate?.recordingDidEncounterError(error)
                completion(nil)
            } else {
                self?.delegate?.recordingDidFinalize(outputURL.path)
                completion(outputURL.path)
            }
        }
    }

    private func concatenateSegments(_ segments: [URL], to outputURL: URL, completion: @escaping (Error?) -> Void) {
        let composition = AVMutableComposition()
        guard let track = composition.addMutableTrack(
            withMediaType: .audio,
            preferredTrackID: kCMPersistentTrackID_Invalid
        ) else {
            completion(NSError(domain: "AudioEngine", code: -1,
                               userInfo: [NSLocalizedDescriptionKey: "Failed to create composition track"]))
            return
        }

        var insertTime = CMTime.zero
        for segment in segments {
            let asset = AVAsset(url: segment)
            guard let assetTrack = asset.tracks(withMediaType: .audio).first else {
                continue
            }
            let timeRange = CMTimeRange(start: .zero, duration: asset.duration)
            do {
                try track.insertTimeRange(timeRange, of: assetTrack, at: insertTime)
                insertTime = CMTimeAdd(insertTime, asset.duration)
            } catch {
                completion(error)
                return
            }
        }

        if insertTime == .zero {
            completion(NSError(domain: "AudioEngine", code: -1,
                               userInfo: [NSLocalizedDescriptionKey: "No audio data found in segments"]))
            return
        }

        guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
            completion(NSError(domain: "AudioEngine", code: -1,
                               userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"]))
            return
        }
        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        exportSession.exportAsynchronously { [weak exportSession] in
            guard let exportSession = exportSession else {
                completion(NSError(domain: "AudioEngine", code: -1,
                                   userInfo: [NSLocalizedDescriptionKey: "Export session was deallocated"]))
                return
            }
            switch exportSession.status {
            case .completed:
                completion(nil)
            case .failed, .cancelled:
                completion(exportSession.error ?? NSError(domain: "AudioEngine", code: -1,
                                                          userInfo: [NSLocalizedDescriptionKey: "Concatenation export failed"]))
            default:
                completion(NSError(domain: "AudioEngine", code: -1,
                                   userInfo: [NSLocalizedDescriptionKey: "Concatenation export ended with status \(exportSession.status.rawValue)"]))
            }
        }
    }

    // MARK: - Paused-recording playback preview

    /// Concatenate the segments captured so far into a temporary preview file
    /// that the caller can hand to the playback engine while the recording is
    /// still paused.
    func prepareForPausedPlayback(completion: @escaping (Result<(url: URL, duration: Double), Error>) -> Void) {
        let isReady: (paused: Bool, segmentsSnapshot: [URL]) = performStateOperation {
            return (paused: isRecording && isPaused, segmentsSnapshot: segmentURLs)
        }

        guard isReady.paused else {
            completion(.failure(NSError(domain: "AudioEngine", code: -1,
                                        userInfo: [NSLocalizedDescriptionKey: "playPausedRecording requires an active, paused recording"])))
            return
        }
        guard !isReady.segmentsSnapshot.isEmpty else {
            completion(.failure(NSError(domain: "AudioEngine", code: -1,
                                        userInfo: [NSLocalizedDescriptionKey: "No audio has been recorded yet to play back"])))
            return
        }

        // Drop any prior preview before generating a new one.
        cleanupPausedPlaybackPreview()

        let previewParent: URL
        if let dir = segmentDirectory {
            previewParent = dir.deletingLastPathComponent()
        } else if let finalURL = finalOutputURL {
            previewParent = finalURL.deletingLastPathComponent()
        } else {
            previewParent = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
                ?? URL(fileURLWithPath: NSTemporaryDirectory())
        }

        let previewFile = previewParent.appendingPathComponent("paused_recording_preview_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")

        // For a single segment, just copy it (no export session needed).
        if isReady.segmentsSnapshot.count == 1 {
            do {
                if FileManager.default.fileExists(atPath: previewFile.path) {
                    try FileManager.default.removeItem(at: previewFile)
                }
                try FileManager.default.copyItem(at: isReady.segmentsSnapshot[0], to: previewFile)
                let duration = CMTimeGetSeconds(AVAsset(url: previewFile).duration)
                self.previewURL = previewFile
                completion(.success((url: previewFile, duration: duration)))
            } catch {
                completion(.failure(error))
            }
            return
        }

        concatenateSegments(isReady.segmentsSnapshot, to: previewFile) { [weak self] error in
            if let error = error {
                completion(.failure(error))
            } else {
                let duration = CMTimeGetSeconds(AVAsset(url: previewFile).duration)
                self?.previewURL = previewFile
                completion(.success((url: previewFile, duration: duration)))
            }
        }
    }

    /// Delete the temporary preview file (if any).
    func cleanupPausedPlaybackPreview() {
        if let url = previewURL {
            try? FileManager.default.removeItem(at: url)
            previewURL = nil
        }
    }

    func getPausedPlaybackPreviewURL() -> URL? {
        return previewURL
    }


    private func setupAssetWriter(inputFormat: AVAudioFormat) throws {
        guard let url = fileURL else { return }
        assetWriter = try AVAssetWriter(outputURL: url, fileType: .m4a)
        let outputSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVNumberOfChannelsKey: Int(currentChannels),
            AVSampleRateKey: currentSampleRate,
            AVEncoderBitRateKey: desiredBitrate
        ]
        let input = AVAssetWriterInput(mediaType: .audio, outputSettings: outputSettings)
        input.expectsMediaDataInRealTime = true
        if let writer = assetWriter, writer.canAdd(input) {
            writer.add(input)
            writerInput = input
            writer.startWriting()
            writerStarted = false
        }
    }

    private func appendToWriter(buffer: AVAudioPCMBuffer, at time: AVAudioTime) {
        guard let input = writerInput, let writer = assetWriter, writer.status == .writing else { return }
        if input.isReadyForMoreMediaData {
            // Create CMBlockBuffer/ CMSampleBuffer from PCM
            var asbd = buffer.format.streamDescription.pointee
            var formatDesc: CMAudioFormatDescription?
            CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: &asbd, layoutSize: 0, layout: nil, magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &formatDesc)

            guard let fmtDesc = formatDesc else { return }
            let frames = CMItemCount(buffer.frameLength)
            let pts = CMTime(seconds: Double(time.sampleTime) / buffer.format.sampleRate, preferredTimescale: 1_000_000_000)

            var blockBuffer: CMBlockBuffer?
            let audioBuffer = buffer.audioBufferList.pointee.mBuffers
            let data = audioBuffer.mData!
            let dataLength = Int(audioBuffer.mDataByteSize)
            let status = CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: data, blockLength: dataLength, blockAllocator: kCFAllocatorNull, customBlockSource: nil, offsetToData: 0, dataLength: dataLength, flags: 0, blockBufferOut: &blockBuffer)
            if status != kCMBlockBufferNoErr { return }

            var sampleBuffer: CMSampleBuffer?
            let sampleStatus = CMAudioSampleBufferCreateWithPacketDescriptions(allocator: kCFAllocatorDefault, dataBuffer: blockBuffer, dataReady: true, makeDataReadyCallback: nil, refcon: nil, formatDescription: fmtDesc, sampleCount: frames, presentationTimeStamp: pts, packetDescriptions: nil, sampleBufferOut: &sampleBuffer)
            if sampleStatus != noErr { return }

            if let sbuf = sampleBuffer {
                input.append(sbuf)
            }
        }
    }

    private func finishWriter() {
        if let input = writerInput {
            input.markAsFinished()
        }
        if let writer = assetWriter {
            writer.finishWriting { [weak self] in
                guard let self = self else { return }
                if writer.status == .failed, let err = writer.error {
                    self.delegate?.recordingDidEncounterError(err)
                }
            }
        }
        writerInput = nil
        assetWriter = nil
        writerStarted = false
    }

    /// Finish writer and wait for completion. Reports writer errors via the
    /// delegate but does NOT emit `recordingDidFinalize` — that fires only
    /// once for the fully assembled recording.
    private func finishWriterAndWait(completion: @escaping () -> Void) {
        if let input = writerInput {
            input.markAsFinished()
        }

        let finalizedPath = fileURL?.path

        if let writer = assetWriter {
            print("[RecordingManager] Finishing asset writer...")
            writer.finishWriting { [weak self] in
                guard let self = self else {
                    completion()
                    return
                }

                if writer.status == .failed, let err = writer.error {
                    print("[RecordingManager] Asset writer failed: \(err.localizedDescription)")
                    self.delegate?.recordingDidEncounterError(err)
                } else if let path = finalizedPath {
                    if FileManager.default.fileExists(atPath: path) {
                        do {
                            let attributes = try FileManager.default.attributesOfItem(atPath: path)
                            let fileSize = attributes[.size] as? Int64 ?? 0
                            print("[RecordingManager] Segment ready: \(fileSize) bytes at \(path)")
                        } catch {
                            print("[RecordingManager] Could not get file attributes: \(error.localizedDescription)")
                        }
                    } else {
                        print("[RecordingManager] Warning: Segment file does not exist at \(path)")
                    }
                }

                completion()
            }
        } else {
            completion()
        }

        writerInput = nil
        assetWriter = nil
        writerStarted = false
    }

    private func encodeAndEmit(buffer: AVAudioPCMBuffer) {
        guard let converter = converter, let aacFormat = aacFormat else { return }

        let maxPacketSize = converter.maximumOutputPacketSize
        let packetCapacity = 1
        guard let compressedBuffer = AVAudioCompressedBuffer(format: aacFormat, packetCapacity: AVAudioPacketCount(packetCapacity), maximumPacketSize: maxPacketSize) as AVAudioCompressedBuffer? else { return }

        var sourceConsumed = false
        var error: NSError?
        _ = converter.convert(to: compressedBuffer, error: &error, withInputFrom: { _, outStatus in
            if sourceConsumed {
                outStatus.pointee = .noDataNow
                return nil
            } else {
                sourceConsumed = true
                outStatus.pointee = .haveData
                return buffer
            }
        })

        if let err = error {
            delegate?.recordingDidEncounterError(err)
        }
    }


    private func configureAudioSessionForRecording() throws {
        let session = AVAudioSession.sharedInstance()

        try session.setCategory(.playAndRecord,
                                mode: .voiceChat,
                                options: [.defaultToSpeaker, .mixWithOthers])

        try session.setPreferredSampleRate(currentSampleRate)
        try session.setPreferredInput(nil)
        try session.setActive(true)
    }

    private func observeAudioSessionInterruptions() {
        guard audioSessionObserver == nil else { return }
        audioSessionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self else { return }
            guard let userInfo = notification.userInfo,
                  let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
                return
            }

            switch type {
            case .began:
                // Log interruption reason for debugging (iOS 14.5+)
                var interruptionReason = "unknown"
                if #available(iOS 14.5, *) {
                    if let reasonValue = userInfo[AVAudioSessionInterruptionReasonKey] as? UInt,
                       let reason = AVAudioSession.InterruptionReason(rawValue: reasonValue) {
                        switch reason {
                        case .default:
                            interruptionReason = "default (likely phone call)"
                        case .builtInMicMuted:
                            interruptionReason = "builtInMicMuted"
                        case .appWasSuspended:
                            interruptionReason = "appWasSuspended"
                        case .routeDisconnected:
                            interruptionReason = "routeDisconnected"
                        @unknown default:
                            interruptionReason = "unknown(\(reasonValue))"
                        }
                    }
                }
                print("[RecordingManager] Interruption began - reason: \(interruptionReason)")

                // Keep session active during interruptions; iOS already pauses our engine.
                // Deactivating here would signal iOS that we no longer need audio,
                // risking app suspension in the background.
                self.performStateOperation {
                    if self.isRecording {
                        if let engine = self.audioEngine, engine.isRunning {
                            engine.pause()
                        }
                        self.isPaused = true
                        self.pauseDurationMonitoring()
                        self.pauseWaveLevelMonitoring()
                        self.delegate?.recordingDidChangeStatus("paused", reason: "interruption", message: "Recording paused due to audio interruption (\(interruptionReason))", recoverable: true)
                        print("[RecordingManager] Recording paused due to interruption - will attempt auto-resume when interruption ends")
                    }
                }
            case .ended:
                print("[RecordingManager] Interruption ended - attempting to resume recording")

                // Log interruption options for debugging (but don't use it to prevent resume)
                // Note: For recording (unlike playback), we ALWAYS attempt to auto-resume
                // because the user explicitly started a recording session and expects it to continue.
                if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                    let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                    print("[RecordingManager] Interruption options - shouldResume: \(options.contains(.shouldResume))")
                }

                // Verify we're still in a recording + paused state
                guard self.performStateOperation({ self.isRecording && self.isPaused }) else {
                    print("[RecordingManager] Skip resume - not in recording+paused state")
                    return
                }

                // Check if microphone is immediately available
                let session = AVAudioSession.sharedInstance()
                let hasInput = session.availableInputs?.isEmpty == false
                let isInputAvailable = session.isInputAvailable

                if !hasInput || !isInputAvailable {
                    print("[RecordingManager] Microphone not immediately available - scheduling delayed retry")
                    // Schedule a delayed retry - the phone may still be releasing resources
                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
                        self?.attemptRecoveryFromInterruption()
                    }
                    return
                }

                // Attempt session reactivation with retry mechanism
                self.attemptSessionReactivationWithRetry()
            @unknown default:
                break
            }
        }
    }

    private func removeAudioSessionInterruptionsObserver() {
        if let obs = audioSessionObserver {
            NotificationCenter.default.removeObserver(obs)
            audioSessionObserver = nil
        }
    }

    // MARK: - Interruption Recovery Methods

    /// Attempts session reactivation with exponential backoff retry mechanism
    private func attemptSessionReactivationWithRetry() {
        let session = AVAudioSession.sharedInstance()
        let maxRetries = 3
        let retryDelays: [TimeInterval] = [0.3, 0.5, 1.0]

        func attemptActivation(attempt: Int) {
            do {
                try session.setActive(true)
                print("[RecordingManager] Session reactivated on attempt \(attempt + 1)")

                // Add stabilization delay before engine restart
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
                    self?.resumeEngineAfterInterruption()
                }
            } catch {
                if attempt < maxRetries - 1 {
                    let delay = retryDelays[attempt]
                    print("[RecordingManager] Session activation failed (attempt \(attempt + 1)/\(maxRetries)), retrying in \(delay)s: \(error.localizedDescription)")
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) {
                        attemptActivation(attempt: attempt + 1)
                    }
                } else {
                    print("[RecordingManager] Session activation failed after \(maxRetries) attempts: \(error.localizedDescription)")
                    self.performStateOperation {
                        if self.isRecording {
                            self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Could not reactivate audio session after interruption: \(error.localizedDescription)", recoverable: false)
                        }
                    }
                }
            }
        }

        attemptActivation(attempt: 0)
    }

    /// Resumes the audio engine after an interruption with proper state validation
    private func resumeEngineAfterInterruption() {
        performStateOperation {
            // Validate we're still in a recording + paused state
            guard self.isRecording && self.isPaused else {
                print("[RecordingManager] Skip engine resume - state changed (isRecording: \(self.isRecording), isPaused: \(self.isPaused))")
                return
            }

            // Re-verify microphone availability
            let session = AVAudioSession.sharedInstance()
            guard session.isInputAvailable, session.availableInputs?.isEmpty == false else {
                print("[RecordingManager] Cannot resume - microphone not available")
                self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Microphone unavailable after interruption ended", recoverable: false)
                return
            }

            // Attempt to restart engine
            do {
                if let engine = self.audioEngine, !engine.isRunning {
                    try engine.start()
                    print("[RecordingManager] Audio engine restarted successfully after interruption")
                }
                self.isPaused = false
                self.resumeDurationMonitoring()
                self.resumeWaveLevelMonitoring()
                self.delegate?.recordingDidChangeStatus("recording", reason: "interruption", message: nil, recoverable: nil)
            } catch {
                print("[RecordingManager] Failed to restart engine after interruption: \(error.localizedDescription)")
                self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Failed to restart recording after interruption: \(error.localizedDescription)", recoverable: false)
            }
        }
    }

    /// Attempts recovery when initial resume fails (e.g., mic was not immediately available)
    private func attemptRecoveryFromInterruption() {
        let session = AVAudioSession.sharedInstance()

        // Re-check microphone availability
        guard session.isInputAvailable, session.availableInputs?.isEmpty == false else {
            print("[RecordingManager] Recovery failed - microphone still unavailable")
            performStateOperation {
                if self.isRecording {
                    self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Unable to resume recording — microphone unavailable", recoverable: false)
                }
            }
            return
        }

        print("[RecordingManager] Attempting recovery - microphone now available")
        attemptSessionReactivationWithRetry()
    }

    private func observeAudioSessionRouteChanges() {
        guard routeChangeObserver == nil else { return }
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self = self else { return }
            guard let userInfo = notification.userInfo,
                  let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
                return
            }

            let session = AVAudioSession.sharedInstance()
            let isInputAvailable = session.isInputAvailable
            let hasInput = session.availableInputs?.isEmpty == false

            self.performStateOperation {
                guard self.isRecording else { return }

                switch reason {
                case .newDeviceAvailable:
                    // New input device available (e.g., headphones plugged in)
                    // If we were paused and mic is now available, try to resume
                    if self.isPaused && isInputAvailable && hasInput {
                        do {
                            if let engine = self.audioEngine, !engine.isRunning {
                                try engine.start()
                            }
                            self.isPaused = false
                            self.resumeDurationMonitoring()
                            self.resumeWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("recording", reason: "routeChange", message: nil, recoverable: nil)
                        } catch {
                            self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Failed to resume after new device connected: \(error.localizedDescription)", recoverable: false)
                        }
                    }

                case .oldDeviceUnavailable:
                    // Input device removed (e.g., headphones unplugged)
                    // Check if built-in mic is still available
                    if !isInputAvailable || !hasInput {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("paused", reason: "routeChange", message: "Microphone input unavailable — device may have been disconnected", recoverable: true)
                        }
                    }

                case .categoryChange:
                    // Audio session category changed - check if we still have mic access
                    if !isInputAvailable || !hasInput {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("paused", reason: "routeChange", message: "Microphone access lost — another app may be recording", recoverable: true)
                        }
                    }

                case .override:
                    // Route was overridden (e.g., phone call routing)
                    if !isInputAvailable {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("paused", reason: "routeChange", message: "Audio route was overridden", recoverable: true)
                        }
                    }

                default:
                    // Other route changes - check availability
                    if !isInputAvailable || !hasInput {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("paused", reason: "routeChange", message: "Microphone unavailable due to audio route change", recoverable: true)
                        }
                    } else if self.isPaused {
                        // Mic became available again - try to resume
                        do {
                            if let engine = self.audioEngine, !engine.isRunning {
                                try engine.start()
                            }
                            self.isPaused = false
                            self.resumeDurationMonitoring()
                            self.resumeWaveLevelMonitoring()
                            self.delegate?.recordingDidChangeStatus("recording", reason: "routeChange", message: nil, recoverable: nil)
                        } catch {
                            self.delegate?.recordingDidChangeStatus("paused", reason: "error", message: "Failed to resume after route change: \(error.localizedDescription)", recoverable: false)
                        }
                    }
                }
            }
        }
    }

    private func removeAudioSessionRouteChangeObserver() {
        if let obs = routeChangeObserver {
            NotificationCenter.default.removeObserver(obs)
            routeChangeObserver = nil
        }
    }

    // MARK: - App Lifecycle Monitoring

    private func observeAppLifecycle() {
        // Observe when app returns from background
        appWillEnterForegroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.validateRecordingStateOnForeground()
        }

        // Observe when app becomes active
        appDidBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            self.validateRecordingStateOnActive()
        }

        // Observe media services reset (critical for extended background)
        mediaServicesResetObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.mediaServicesWereResetNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            print("[RecordingManager] Media services were reset - attempting to recover")
            self.handleMediaServicesReset()
        }
    }

    private func removeAppLifecycleObservers() {
        if let obs = appWillEnterForegroundObserver {
            NotificationCenter.default.removeObserver(obs)
            appWillEnterForegroundObserver = nil
        }
        if let obs = appDidBecomeActiveObserver {
            NotificationCenter.default.removeObserver(obs)
            appDidBecomeActiveObserver = nil
        }
        if let obs = mediaServicesResetObserver {
            NotificationCenter.default.removeObserver(obs)
            mediaServicesResetObserver = nil
        }
    }

    // Validate recording state when app enters foreground
    private func validateRecordingStateOnForeground() {
        performStateOperation {
            // Only validate if we think we're recording
            guard isRecording else { return }

            print("[RecordingManager] App entering foreground - validating recording state")

            // If recording is paused (e.g., due to interruption), don't treat stopped engine as an error.
            // The engine was intentionally paused and we're waiting for the interruption to end.
            if isPaused {
                print("[RecordingManager] Recording is paused (likely due to interruption) - skipping aggressive validation")

                // Just verify we still have the audio engine instance
                if audioEngine == nil {
                    print("[RecordingManager] ERROR: Audio engine is nil while paused - cannot recover")
                    handleRecordingLost()
                    return
                }

                // Check if microphone will be available when we try to resume
                let session = AVAudioSession.sharedInstance()
                let isInputAvailable = session.isInputAvailable
                let hasInput = session.availableInputs?.isEmpty == false

                if !isInputAvailable && !hasInput {
                    print("[RecordingManager] Warning: Microphone not currently available - will check again when interruption ends")
                }

                print("[RecordingManager] Paused recording state validated - waiting for interruption to end")
                return
            }

            // For active (non-paused) recording, perform full validation

            // Check if audio engine is actually running
            guard let engine = audioEngine else {
                print("[RecordingManager] ERROR: Audio engine is nil but state says recording")
                handleRecordingLost()
                return
            }

            if !engine.isRunning {
                print("[RecordingManager] ERROR: Audio engine stopped but state says active recording")
                handleRecordingLost()
                return
            }

            // Check audio session
            let session = AVAudioSession.sharedInstance()
            let isInputAvailable = session.isInputAvailable
            let hasInput = session.availableInputs?.isEmpty == false

            if !isInputAvailable || !hasInput {
                print("[RecordingManager] ERROR: Microphone not available after foreground")
                handleRecordingLost()
                return
            }

            // Verify tap is still installed
            if !inputNodeTapInstalled {
                print("[RecordingManager] ERROR: Input tap removed but state says recording")
                handleRecordingLost()
                return
            }

            // Check if audio session is still active
            do {
                if !session.isOtherAudioPlaying {
                    // Try to reactivate if needed
                    try session.setActive(true)
                }
                print("[RecordingManager] Recording state validated successfully")
            } catch {
                print("[RecordingManager] ERROR: Cannot reactivate audio session: \(error)")
                handleRecordingLost()
            }
        }
    }

    // Validate when app becomes active (after unlock)
    private func validateRecordingStateOnActive() {
        performStateOperation {
            guard isRecording else { return }

            print("[RecordingManager] App became active - checking recording health")

            // If recording is paused (e.g., due to interruption), skip writer status check
            // as the writer may be in an intermediate state while waiting for resume
            if isPaused {
                print("[RecordingManager] Recording is paused - skipping active state validation")
                return
            }

            // Additional check: verify we're actually receiving audio data
            // by checking if the asset writer is still writing
            if let writer = assetWriter {
                if writer.status == .failed {
                    print("[RecordingManager] ERROR: Asset writer failed - status: \(writer.status.rawValue)")
                    if let error = writer.error {
                        print("[RecordingManager] Writer error: \(error.localizedDescription)")
                    }
                    handleRecordingLost()
                    return
                } else if writer.status == .cancelled {
                    print("[RecordingManager] ERROR: Asset writer cancelled")
                    handleRecordingLost()
                    return
                }
            }
        }
    }

    // Handle media services reset (happens after crash or extended background)
    private func handleMediaServicesReset() {
        performStateOperation {
            guard isRecording else { return }

            print("[RecordingManager] Attempting to recover from media services reset")

            // Media services reset means all audio objects are invalid
            // We need to stop and notify the user

            // Clean up invalid objects
            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.stop()
            }

            // Finish writer if exists
            if assetWriter != nil {
                finishWriter()
            }

            audioEngine = nil
            converter = nil
            aacFormat = nil
            isRecording = false
            isPaused = false

            stopDurationMonitoring()
            stopWaveLevelMonitoring()
            stopRecordingHealthCheck()

            // Notify delegate about the failure
            delegate?.recordingDidChangeStatus("stopped", reason: "mediaReset", message: "Recording lost due to system audio reset. Please start a new recording.", recoverable: false)
        }
    }

    // Handle when recording was silently stopped
    private func handleRecordingLost() {
        print("[RecordingManager] Recording was lost - cleaning up and notifying UI")

        // Clean up resources
        if let engine = audioEngine {
            if inputNodeTapInstalled {
                engine.inputNode.removeTap(onBus: 0)
                inputNodeTapInstalled = false
            }
            engine.stop()
        }

        if assetWriter != nil {
            finishWriter()
        }

        audioEngine = nil
        converter = nil
        aacFormat = nil
        isRecording = false
        isPaused = false

        stopDurationMonitoring()
        stopWaveLevelMonitoring()
        stopRecordingHealthCheck()

        // Notify the UI
        delegate?.recordingDidChangeStatus("stopped", reason: "error", message: "Recording was interrupted by the system. The microphone is no longer available. Please start a new recording.", recoverable: false)
    }

    // MARK: - Recording Health Check

    private func startRecordingHealthCheck() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            // Check every 30 seconds
            self.healthCheckTimer = Timer.scheduledTimer(withTimeInterval: 30.0, repeats: true) { [weak self] _ in
                self?.performRecordingHealthCheck()
            }
        }
    }

    private func stopRecordingHealthCheck() {
        healthCheckTimer?.invalidate()
        healthCheckTimer = nil
    }

    private func performRecordingHealthCheck() {
        performStateOperation {
            guard isRecording && !isPaused else { return }

            // Verify engine is still running
            guard let engine = audioEngine, engine.isRunning else {
                print("[RecordingManager] Health check FAILED: Engine not running")
                handleRecordingLost()
                return
            }

            // Verify writer is healthy
            if let writer = assetWriter, writer.status != .writing {
                print("[RecordingManager] Health check FAILED: Writer status: \(writer.status.rawValue)")
                handleRecordingLost()
                return
            }

            print("[RecordingManager] Health check PASSED")
        }
    }

    // MARK: - Duration Monitoring

    private func startDurationMonitoring() {
        stopDurationMonitoring()
        print("[RecordingManager] Starting duration monitoring for recording")

        isDurationMonitoring = true
        isDurationPaused = false
        currentDuration = 0.0

        // Schedule on main run loop to ensure timer fires
        DispatchQueue.main.async { [weak self] in
            guard let strongSelf = self else { return }
            strongSelf.durationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                if !self.isDurationPaused && self.isDurationMonitoring {
                    self.currentDuration += 1.0
                    self.delegate?.recordingDidUpdateDuration(self.currentDuration)
                }
            }
        }
    }

    private func stopDurationMonitoring() {
        durationTimer?.invalidate()
        durationTimer = nil
        isDurationMonitoring = false
        isDurationPaused = false
        print("[RecordingManager] Duration monitoring stopped")
    }

    private func pauseDurationMonitoring() {
        isDurationPaused = true
        print("[RecordingManager] Duration monitoring paused")
    }

    private func resumeDurationMonitoring() {
        isDurationPaused = false
        print("[RecordingManager] Duration monitoring resumed")
    }

    // MARK: - Wave Level Monitoring

    private func startWaveLevelMonitoring() {
        stopWaveLevelMonitoring()
        print("[RecordingManager] Starting wave level monitoring for recording")

        isWaveLevelMonitoring = true
        isWaveLevelPaused = false

        // Schedule on main run loop to ensure timer fires
        DispatchQueue.main.async { [weak self] in
            guard let strongSelf = self else { return }
            strongSelf.waveLevelTimer = Timer.scheduledTimer(withTimeInterval: strongSelf.waveLevelEmissionInterval, repeats: true) { [weak self] _ in
                guard let self = self else { return }
                if !self.isWaveLevelPaused && self.isWaveLevelMonitoring {
                    // Calculate wave level from audio engine
                    let level = self.calculateWaveLevel()
                    let timestamp = Date().timeIntervalSince1970
                    self.delegate?.recordingDidEmitWaveLevel(level, timestamp: timestamp)
                }
            }
        }
    }

    private func stopWaveLevelMonitoring() {
        waveLevelTimer?.invalidate()
        waveLevelTimer = nil
        isWaveLevelMonitoring = false
        isWaveLevelPaused = false
        print("[RecordingManager] Wave level monitoring stopped")
    }

    private func pauseWaveLevelMonitoring() {
        isWaveLevelPaused = true
        print("[RecordingManager] Wave level monitoring paused")
    }

    private func resumeWaveLevelMonitoring() {
        isWaveLevelPaused = false
        print("[RecordingManager] Wave level monitoring resumed")
    }

    private func calculateWaveLevel() -> Double {
        // For now, return a simple calculated value based on audio engine state
        // In a real implementation, this would calculate RMS from the audio buffer
        guard let audioEngine = audioEngine, audioEngine.isRunning else {
            return 0.0
        }

        // Simple wave level calculation - in practice, this would use the audio tap buffer
        // For now, return a random value between 0.0 and 1.0 to simulate audio levels
        return Double.random(in: 0.0...1.0)
    }

    // MARK: - Public Methods

    func setWaveLevelEmissionInterval(_ intervalMs: Int) {
        let clampedInterval = max(50, min(500, intervalMs))
        waveLevelEmissionInterval = TimeInterval(clampedInterval) / 1000.0
        print("[RecordingManager] Wave level emission interval set to: \(clampedInterval)ms")
    }

    func getCurrentDuration() -> Double {
        return currentDuration
    }

    func getStatus() -> (status: String, duration: Double, path: String?) {
        return performStateOperation {
            let statusString: String
            if !isRecording {
                statusString = "idle"
            } else if isPaused {
                statusString = "paused"
            } else {
                statusString = "recording"
            }
            return (status: statusString, duration: currentDuration, path: finalOutputURL?.path)
        }
    }
}
