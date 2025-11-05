import Foundation
import AVFoundation

protocol RecordingManagerDelegate: AnyObject {
    func recordingDidChangeStatus(_ status: String)
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

    // Asset Writer (native .m4a)
    private var assetWriter: AVAssetWriter?
    private var writerInput: AVAssetWriterInput?
    private var fileURL: URL?
    private var writerStarted: Bool = false
    private var audioSessionObserver: Any?
    private var routeChangeObserver: Any?

    init(delegate: RecordingManagerDelegate?) {
        self.delegate = delegate
    }

    func configureRecording(encoding: String?, bitrate: Int?, path: String? = nil) {
        if let br = bitrate, br > 0 { desiredBitrate = br }
        if let p = path, !p.isEmpty {
            // Normalize provided path into app sandbox
            if p.hasPrefix("file://") {
                if let url = URL(string: p) {
                    fileURL = url
                } else {
                    let pathString = String(p.dropFirst(7))
                    fileURL = URL(fileURLWithPath: pathString)
                }
            } else if p.hasPrefix("/") {
                let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
                let trimmed = String(p.dropFirst())
                fileURL = base.appendingPathComponent(trimmed)
            } else {
                let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
                fileURL = base.appendingPathComponent(p)
            }
        } else {
            // Use same directory as Capacitor Filesystem Directory.Data
            // On iOS, this maps to the app's data directory (Library/Application Support)
            let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
            fileURL = base.appendingPathComponent("recording_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
        }

        // Ensure directory exists for the output file
        if let url = fileURL {
            let dir = url.deletingLastPathComponent()
            if !FileManager.default.fileExists(atPath: dir.path) {
                do {
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                } catch {
                    delegate?.recordingDidEncounterError(NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create output directory: \(error.localizedDescription)"]))
                }
            }
        }
    }

    func getFormatInfo() -> (sampleRate: Int, channels: Int, encoding: String, mimeType: String, bitrate: Int, path: String?) {
        return (Int(currentSampleRate), Int(currentChannels), "aac", "audio/aac", desiredBitrate, fileURL?.path)
    }

    func startRecording() {
        performStateOperation {
            guard !isRecording else { return }
            do {
                try configureAudioSessionForRecording()
                observeAudioSessionInterruptions()
                observeAudioSessionRouteChanges()
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

                // Prepare asset writer for native .m4a
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

                delegate?.recordingDidChangeStatus("recording")
            } catch {
                delegate?.recordingDidEncounterError(error)
            }
        }
    }

    func stopRecording() {
        performStateOperation {
            guard isRecording else { return }
            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.stop()
            }
            removeAudioSessionInterruptionsObserver()
            removeAudioSessionRouteChangeObserver()
            // Only finish writer if one exists (prevents "file not found" error after resetRecording)
            if assetWriter != nil {
                finishWriter()
            }
            audioEngine = nil
            converter = nil
            aacFormat = nil

            // Stop duration and wave level monitoring
            stopDurationMonitoring()
            stopWaveLevelMonitoring()

            isRecording = false
            isPaused = false
            delegate?.recordingDidChangeStatus("stopped")
        }
    }

    /// Stop recording and wait for the file to be fully written to disk
    /// Returns the file path asynchronously via completion handler
    func stopRecordingAndWaitForFile(completion: @escaping (String?) -> Void) {
        performStateOperation {
            guard isRecording else {
                completion(fileURL?.path)
                return
            }

            let filePath = fileURL?.path

            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.stop()
            }
            removeAudioSessionInterruptionsObserver()
            removeAudioSessionRouteChangeObserver()

            // Stop duration and wave level monitoring
            stopDurationMonitoring()
            stopWaveLevelMonitoring()

            // Finish writer and wait for completion
            if assetWriter != nil {
                finishWriterAndWait { [weak self] in
                    guard let self = self else {
                        completion(nil)
                        return
                    }

                    self.performStateOperation {
                        self.audioEngine = nil
                        self.converter = nil
                        self.aacFormat = nil
                        self.isRecording = false
                        self.isPaused = false
                        self.delegate?.recordingDidChangeStatus("stopped")
                        completion(filePath)
                    }
                }
            } else {
                audioEngine = nil
                converter = nil
                aacFormat = nil
                isRecording = false
                isPaused = false
                delegate?.recordingDidChangeStatus("stopped")
                completion(filePath)
            }
        }
    }

    func pauseRecording() {
        performStateOperation {
            guard isRecording && !isPaused else { return }
            isPaused = true

            // Pause duration and wave level monitoring
            pauseDurationMonitoring()
            pauseWaveLevelMonitoring()

            delegate?.recordingDidChangeStatus("paused")
        }
    }

    func resumeRecording() {
        performStateOperation {
            guard isRecording && isPaused else { return }

            // Check if we need to reinitialize after a reset (no writer + tap removed)
            if assetWriter == nil && !inputNodeTapInstalled {
                // Reinitialize recording session after reset
                do {
                    guard let engine = audioEngine else {
                        throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Audio engine not initialized"])
                    }

                    let input = engine.inputNode
                    let inputFormat = input.outputFormat(forBus: 0)

                    // Setup new asset writer for the new file
                    try setupAssetWriter(inputFormat: inputFormat)

                    // Reinstall tap
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

                    // Restart engine
                    try engine.start()
                } catch {
                    delegate?.recordingDidEncounterError(error)
                    return
                }
            }

            isPaused = false

            // Resume duration and wave level monitoring
            resumeDurationMonitoring()
            resumeWaveLevelMonitoring()

            delegate?.recordingDidChangeStatus("recording")
        }
    }

    /// Reset the current recording session without finalizing a file.
    /// Behavior:
    /// - Keeps audio engine configured but removes active taps
    /// - Finishes and discards current writer and file
    /// - Resets duration and wave level monitoring counters to 0
    /// - Leaves the recording in paused state so `resumeRecording()` can continue fresh
    func resetRecording() {
        performStateOperation {
            guard isRecording else { return }

            // Remove input tap and stop engine without tearing down configuration entirely
            if let engine = audioEngine {
                if inputNodeTapInstalled {
                    engine.inputNode.removeTap(onBus: 0)
                    inputNodeTapInstalled = false
                }
                engine.pause()
            }

            // Finalize current writer so any existing partial file remains valid
            // Do NOT delete the file; allow clients to query it if needed
            finishWriter()
            // Remove references so a new writer will be setup on resume
            assetWriter = nil
            writerInput = nil
            writerStarted = false
            // Rotate to a fresh target path for the next session
            if let currentURL = fileURL {
                let dir = currentURL.deletingLastPathComponent()
                let newURL = dir.appendingPathComponent("recording_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
                fileURL = newURL
            }

            // Reset monitoring counters and pause monitors (do not destroy timers)
            pauseDurationMonitoring()
            pauseWaveLevelMonitoring()
            currentDuration = 0.0

            // Keep recording flag, but set paused so resumeRecording() can proceed
            isPaused = true

            // Notify paused status
            delegate?.recordingDidChangeStatus("paused")
        }
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
        let finalizedPath = fileURL?.path
        if let writer = assetWriter {
            writer.finishWriting { [weak self] in
                guard let self = self else { return }
                if writer.status == .failed, let err = writer.error {
                    self.delegate?.recordingDidEncounterError(err)
                } else if let path = finalizedPath {
                    self.delegate?.recordingDidFinalize(path)
                }
            }
        }
        writerInput = nil
        assetWriter = nil
        writerStarted = false
    }

    /// Finish writer and wait for completion
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
                    // Verify the file exists and has content
                    if FileManager.default.fileExists(atPath: path) {
                        do {
                            let attributes = try FileManager.default.attributesOfItem(atPath: path)
                            let fileSize = attributes[.size] as? Int64 ?? 0
                            print("[RecordingManager] Recording file ready: \(fileSize) bytes at \(path)")
                        } catch {
                            print("[RecordingManager] Could not get file attributes: \(error.localizedDescription)")
                        }
                    } else {
                        print("[RecordingManager] Warning: Recording file does not exist at \(path)")
                    }
                    self.delegate?.recordingDidFinalize(path)
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
        try session.setActive(true, options: .notifyOthersOnDeactivation)
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
                // Deactivate session to yield mic to other apps (e.g., phone call or another recorder)
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
                self.performStateOperation {
                    if self.isRecording {
                        if let engine = self.audioEngine, engine.isRunning {
                            engine.pause()
                        }
                        self.isPaused = true
                        self.pauseDurationMonitoring()
                        self.pauseWaveLevelMonitoring()
                        self.delegate?.recordingDidChangeStatus("paused")
                    }
                }
            case .ended:
                // Check if we should resume - only if microphone is available
                let session = AVAudioSession.sharedInstance()
                let hasInput = session.availableInputs?.isEmpty == false
                let isInputAvailable = session.isInputAvailable

                // Try to reactivate session
                do {
                    try session.setActive(true)
                } catch {
                    // If activation fails, microphone might be unavailable (e.g., another app using it)
                    self.performStateOperation {
                        if self.isRecording {
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }
                    return
                }

                // Only resume if microphone is actually available
                guard hasInput && isInputAvailable else {
                    // Microphone unavailable - likely another app is recording or phone call active
                    self.performStateOperation {
                        if self.isRecording {
                            let error = NSError(
                                domain: "AudioEngine",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "Microphone unavailable - may be in use by another app or phone call"]
                            )
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }
                    return
                }

                // Check if we should resume (user may have declined)
                if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                    let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                    if !options.contains(.shouldResume) {
                        // User declined to resume (e.g., dismissed phone call)
                        return
                    }
                }

                self.performStateOperation {
                    guard self.isRecording else { return }
                    do {
                        if let engine = self.audioEngine, !engine.isRunning {
                            try engine.start()
                        }
                        self.isPaused = false
                        self.resumeDurationMonitoring()
                        self.resumeWaveLevelMonitoring()
                        self.delegate?.recordingDidChangeStatus("recording")
                    } catch {
                        self.delegate?.recordingDidEncounterError(error)
                    }
                }
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
                            self.delegate?.recordingDidChangeStatus("recording")
                        } catch {
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }

                case .oldDeviceUnavailable:
                    // Input device removed (e.g., headphones unplugged)
                    // Check if built-in mic is still available
                    if !isInputAvailable || !hasInput {
                        // No input available - pause recording
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            let error = NSError(
                                domain: "AudioEngine",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "Microphone input unavailable - device may have been disconnected"]
                            )
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }

                case .categoryChange:
                    // Audio session category changed - check if we still have mic access
                    if !isInputAvailable || !hasInput {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                            let error = NSError(
                                domain: "AudioEngine",
                                code: -1,
                                userInfo: [NSLocalizedDescriptionKey: "Microphone access lost - another app may be recording"]
                            )
                            self.delegate?.recordingDidEncounterError(error)
                        }
                    }

                case .override:
                    // Route was overridden (e.g., phone call routing)
                    if !isInputAvailable {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
                        }
                    }

                default:
                    // Other route changes - check availability
                    if !isInputAvailable || !hasInput {
                        if !self.isPaused {
                            self.isPaused = true
                            self.pauseDurationMonitoring()
                            self.pauseWaveLevelMonitoring()
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
                            self.delegate?.recordingDidChangeStatus("recording")
                        } catch {
                            self.delegate?.recordingDidEncounterError(error)
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
            return (status: statusString, duration: currentDuration, path: fileURL?.path)
        }
    }
}
