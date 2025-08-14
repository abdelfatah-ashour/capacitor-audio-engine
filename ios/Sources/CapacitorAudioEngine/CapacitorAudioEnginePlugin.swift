import Foundation
import Capacitor
@preconcurrency import AVFoundation
@preconcurrency import UserNotifications

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */

@objc(CapacitorAudioEnginePlugin)
public class CapacitorAudioEnginePlugin: CAPPlugin, CAPBridgedPlugin, RecordingManagerDelegate, PlaybackManagerDelegate, WaveformDataManager.WaveformEventCallback {
    public let identifier = "CapacitorAudioEnginePlugin"
    public let jsName = "CapacitorAudioEngine"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermission", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getDuration", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "trimAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isMicrophoneBusy", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAvailableMicrophones", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "switchMicrophone", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "configureWaveform", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "configureWaveformSpeechDetection", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyWaveform", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addListener", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "removeAllListeners", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAudioInfo", returnType: CAPPluginReturnPromise),
        // Playback methods
        CAPPluginMethod(name: "preloadTracks", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "playAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "seekAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToNext", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToPrevious", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToIndex", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPlaybackInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
    ]

    // Add a property for RecordingManager
    private var recordingManager: RecordingManager!

    // Add a property for PlaybackManager
    private var playbackManager: PlaybackManager!

    // Add a property for WaveformDataManager
    private var waveformDataManager: WaveformDataManager!

    // Add property for pending recording call
    private var pendingStopRecordingCall: CAPPluginCall?

    // MARK: - Thread Safety

    private let stateQueue = DispatchQueue(label: "audio-engine-state", qos: .userInteractive)

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    // MARK: - Playback Info Caching
    private var cachedPlaybackInfo: [String: Any]?
    private var lastPlaybackInfoUpdate: TimeInterval = 0
    private let playbackInfoCacheInterval: TimeInterval = 0.5 // Cache for 500ms

    private func invalidatePlaybackInfoCache() {
        cachedPlaybackInfo = nil
        lastPlaybackInfoUpdate = 0
    }

    // MARK: - Logging Utility

    private func log(_ message: String) {
        #if DEBUG
        print("[AudioEngine] \(message)")
        #endif
    }

    // MARK: - Network Utility Methods

    private func checkNetworkAvailability() -> Bool {
        // Simplified network check - assume network is available
        return true
    }

    private func isRemoteURL(_ urlString: String) -> Bool {
        return urlString.hasPrefix("http://") || urlString.hasPrefix("https://")
    }

    private func createOptimizedURLRequest(from urlString: String) -> URLRequest? {
        guard let url = URL(string: urlString) else { return nil }

        var request = URLRequest(url: url)

        // Enhanced headers for better CDN compatibility
        request.setValue("CapacitorAudioEngine/1.0 (iOS)", forHTTPHeaderField: "User-Agent")
        request.setValue("audio/*", forHTTPHeaderField: "Accept")
        request.setValue("bytes", forHTTPHeaderField: "Accept-Ranges")
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        request.setValue("keep-alive", forHTTPHeaderField: "Connection")

        // Set appropriate timeout for CDN files
        request.timeoutInterval = AudioEngineConstants.networkTimeout

        // Add range header for partial content support
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        return request
    }

    @objc func checkPermission(_ call: CAPPluginCall) {
        Task {
            let audioStatus = AVAudioSession.sharedInstance().recordPermission
            let audioGranted = audioStatus == .granted

            // Check notification permission status asynchronously
            let notificationGranted: Bool
            if #available(iOS 10.0, *) {
                // Use async API on iOS 15+ or fallback to callback-based API
                if #available(iOS 15.0, *) {
                    let settings = await UNUserNotificationCenter.current().notificationSettings()
                    notificationGranted = settings.authorizationStatus == .authorized
                } else {
                    // Fallback for iOS 10-14: use callback-based API
                    notificationGranted = await withCheckedContinuation { continuation in
                        UNUserNotificationCenter.current().getNotificationSettings { settings in
                            let granted = settings.authorizationStatus == .authorized
                            continuation.resume(returning: granted)
                        }
                    }
                }
            } else {
                notificationGranted = true
            }

            // Return to main actor for Capacitor callback
            await MainActor.run {
                call.resolve([
                    "granted": audioGranted && notificationGranted,
                    "audioPermission": audioGranted,
                    "notificationPermission": notificationGranted
                ])
            }
        }
    }

    override public func load() {
        // Initialize the recording manager and set self as delegate
        recordingManager = RecordingManager(delegate: self)

        // Initialize the playback manager and set self as delegate
        playbackManager = PlaybackManager()
        playbackManager.delegate = self

        // Initialize the waveform data manager and set self as delegate
        waveformDataManager = WaveformDataManager(eventCallback: self)

        // Set up initial audio session that supports both recording and playback
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord,
                                       mode: .default,
                                       options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker, .mixWithOthers, .duckOthers])
            // Don't activate the session yet - let individual managers handle activation
            log("Initial audio session configured for recording and playback")
        } catch {
            log("Warning: Failed to configure initial audio session: \(error.localizedDescription)")
        }
    }

    @objc func requestPermission(_ call: CAPPluginCall) {
        // First request audio permission
        AVAudioSession.sharedInstance().requestRecordPermission { audioGranted in
            // Then check/request notification permission on iOS 10+
            if #available(iOS 10.0, *) {
                UNUserNotificationCenter.current().getNotificationSettings { settings in
                    if settings.authorizationStatus == .notDetermined {
                        // Request notification permission
                        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { notificationGranted, error in
                            DispatchQueue.main.async {
                                call.resolve([
                                    "granted": audioGranted && notificationGranted,
                                    "audioPermission": audioGranted,
                                    "notificationPermission": notificationGranted
                                ])
                            }
                        }
                    } else {
                        let notificationGranted = settings.authorizationStatus == .authorized
                        DispatchQueue.main.async {
                            call.resolve([
                                "granted": audioGranted && notificationGranted,
                                "audioPermission": audioGranted,
                                "notificationPermission": notificationGranted
                            ])
                        }
                    }
                }
            } else {
                // iOS < 10, assume notification permission is granted
                call.resolve([
                    "granted": audioGranted,
                    "audioPermission": audioGranted,
                    "notificationPermission": true
                ])
            }
        }
    }

    // MARK: - Recording Methods (delegate to RecordingManager)

    @objc func startRecording(_ call: CAPPluginCall) {
        // Input validation
        if let sampleRate = call.getInt("sampleRate"), sampleRate <= 0 {
            call.reject("Invalid sample rate: must be positive")
            return
        }

        if let channels = call.getInt("channels"), channels <= 0 || channels > 2 {
            call.reject("Invalid channels: must be 1 or 2")
            return
        }

        if let bitrate = call.getInt("bitrate"), bitrate <= 0 {
            call.reject("Invalid bitrate: must be positive")
            return
        }

        // Apply quality preset if specified
        var sampleRate = call.getInt("sampleRate") ?? Int(AudioEngineConstants.defaultSampleRate)
        var bitrate = call.getInt("bitrate") ?? AudioEngineConstants.defaultBitrate

        if let quality = call.getString("quality") {
            switch quality {
            case "low":
                sampleRate = Int(AudioEngineConstants.QualityPresets.Low.sampleRate)
                bitrate = AudioEngineConstants.QualityPresets.Low.bitrate
            case "medium":
                sampleRate = Int(AudioEngineConstants.QualityPresets.Medium.sampleRate)
                bitrate = AudioEngineConstants.QualityPresets.Medium.bitrate
            case "high":
                sampleRate = Int(AudioEngineConstants.QualityPresets.High.sampleRate)
                bitrate = AudioEngineConstants.QualityPresets.High.bitrate
            default:
                // Keep user-specified or default values
                break
            }
        }

        let settings: [String: Any] = [
            "sampleRate": sampleRate,
            "channels": call.getInt("channels") ?? AudioEngineConstants.defaultChannels,
            "bitrate": bitrate,
            "maxDuration": call.getInt("maxDuration") as Any
        ]

        recordingManager.startRecording(with: settings)

        // Start waveform data monitoring for real-time audio levels
        waveformDataManager.startMonitoring()

        call.resolve()
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        recordingManager.pauseRecording()

        // Pause waveform data monitoring
        waveformDataManager.pauseMonitoring()

        call.resolve()
    }

    @objc func resumeRecording(_ call: CAPPluginCall) {
        recordingManager.resumeRecording()

        // Resume waveform data monitoring
        waveformDataManager.resumeMonitoring()

        call.resolve()
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        // Store the call to resolve it when recording finishes
        pendingStopRecordingCall = call
        recordingManager.stopRecording()
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        let duration = recordingManager.getDuration()
        call.resolve(["duration": duration])
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        let status = recordingManager.getStatus()
        call.resolve(["status": status])
    }

    @objc func trimAudio(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri"),
              let start = call.getDouble("start"),
              let end = call.getDouble("end") else {
            call.reject("URI, start, and end are required")
            return
        }

        Task {
            do {
                let trimmedURL = try await recordingManager.trimAudio(uri: uri, start: start, end: end)
                var audioInfo = try await extractAudioInfo(from: trimmedURL.absoluteString)

                // Update path fields to use the trimmed file's actual paths
                audioInfo["path"] = trimmedURL.path
                audioInfo["uri"] = trimmedURL.absoluteString
                audioInfo["webPath"] = "capacitor://localhost/_capacitor_file_" + trimmedURL.path

                // Generate base64 data for the trimmed file
                let audioData = try Data(contentsOf: trimmedURL)
                let base64String = try await audioData.base64StringWithOptionalCompression(useCompression: true)
                audioInfo["base64"] = base64String

                call.resolve(audioInfo)
            } catch {
                call.reject("Failed to trim audio: \(error.localizedDescription)")
            }
        }
    }

    @objc func isMicrophoneBusy(_ call: CAPPluginCall) {
        let result = recordingManager.isMicrophoneBusy()
        call.resolve([
            "busy": result.isBusy,
            "reason": result.reason
        ])
    }

    @objc func getAvailableMicrophones(_ call: CAPPluginCall) {
        let microphones = recordingManager.getAvailableMicrophones()
        call.resolve(["microphones": microphones])
    }

    @objc func switchMicrophone(_ call: CAPPluginCall) {
        guard let id = call.getInt("id") else {
            call.reject("Microphone ID is required")
            return
        }
        recordingManager.switchMicrophone(to: id)
        call.resolve()
    }

    @objc func configureWaveform(_ call: CAPPluginCall) {
        let numberOfBars = call.getInt("numberOfBars") ?? 32
        let debounceInSeconds = call.getDouble("debounceInSeconds")

        if let debounceInSeconds = debounceInSeconds {
            // Configure both emission interval and bars
            waveformDataManager.configureWaveform(debounceInSeconds: Float(debounceInSeconds), bars: numberOfBars)
            log("Waveform configured with \(numberOfBars) bars and \(debounceInSeconds) seconds emission interval")
        } else {
            // Configure only bars
            waveformDataManager.setNumberOfBars(numberOfBars)
            log("Waveform configured with \(numberOfBars) bars")
        }

        let result: [String: Any] = [
            "success": true,
            "numberOfBars": numberOfBars
        ]
        call.resolve(result)
    }

    @objc func configureWaveformSpeechDetection(_ call: CAPPluginCall) {
        let enabled = call.getBool("enabled") ?? false
        let threshold = call.getDouble("threshold") ?? 0.02
        let useVAD = call.getBool("useVAD") ?? true
        let calibrationDuration = call.getInt("calibrationDuration") ?? 1000

        waveformDataManager.configureSpeechDetection(
            enabled: enabled,
            threshold: Float(threshold),
            useVAD: useVAD,
            calibrationDuration: calibrationDuration
        )

        log("Speech detection configured - enabled: \(enabled), threshold: \(threshold), VAD: \(useVAD), calibration: \(calibrationDuration)ms")

        let result: [String: Any] = [
            "success": true,
            "enabled": enabled,
            "threshold": threshold,
            "useVAD": useVAD,
            "calibrationDuration": calibrationDuration
        ]
        call.resolve(result)
    }

    @objc func destroyWaveform(_ call: CAPPluginCall) {
        // Stop monitoring if active
        if waveformDataManager.isMonitoring() {
            log("Stopping waveform monitoring before destruction")
        }

        // Cleanup waveform resources
        waveformDataManager.cleanup()
        log("Waveform configuration destroyed and resources cleaned up")

        call.resolve()
    }

    @objc func getAudioInfo(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        Task {
            do {
                let audioInfo = try await extractAudioInfo(from: uri)
                call.resolve(audioInfo)
            } catch {
                call.reject("Failed to get audio info: \(error.localizedDescription)")
            }
        }
    }

    private func extractAudioInfo(from uri: String) async throws -> [String: Any] {
        let asset: AVAsset

        // Check if it's a remote URL or local file
        if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
            // Remote URL
            guard let url = URL(string: uri) else {
                throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])
            }

            // Create asset with options for better remote handling
            let options = [AVURLAssetPreferPreciseDurationAndTimingKey: true]
            asset = AVURLAsset(url: url, options: options)
        } else {
            // Local file URI
            let fileURL: URL
            if uri.hasPrefix("file://") {
                guard let url = URL(string: uri) else {
                    throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid file URI"])
                }
                fileURL = url
            } else {
                // Assume it's a path
                fileURL = URL(fileURLWithPath: uri)
            }

            // Check if file exists
            guard FileManager.default.fileExists(atPath: fileURL.path) else {
                throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "File does not exist"])
            }

            asset = AVAsset(url: fileURL)
        }

        // Load asset properties with iOS version compatibility
        let duration: CMTime
        let tracks: [AVAssetTrack]

        if #available(iOS 15.0, *) {
            // Use async loading for iOS 15+
            duration = try await asset.load(.duration)
            tracks = try await asset.load(.tracks)
        } else {
            // Use synchronous loading for older iOS versions
            duration = asset.duration
            tracks = asset.tracks
        }

        // Find the audio track
        var audioTrack: AVAssetTrack?

        if #available(iOS 15.0, *) {
            // Use async loading for iOS 15+
            var audioTracks: [AVAssetTrack] = []
            for track in tracks {
                // For mediaType, we can still use the synchronous property as it's available immediately
                let mediaType = track.mediaType
                if mediaType == AVMediaType.audio {
                    audioTracks.append(track)
                }
            }
            audioTrack = audioTracks.first
        } else {
            // Use synchronous loading for older iOS versions
            audioTrack = tracks.first { track in
                return track.mediaType == AVMediaType.audio
            }
        }

        guard let audioTrack = audioTrack else {
            throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "No audio track found"])
        }

        // Load audio track properties with version compatibility
        var formatDescriptions: [CMFormatDescription]

        if #available(iOS 15.0, *) {
            let loadedDescriptions = try await audioTrack.load(.formatDescriptions)
            formatDescriptions = loadedDescriptions
        } else {
            guard let descriptions = audioTrack.formatDescriptions as? [CMFormatDescription] else {
                throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to get format descriptions"])
            }
            formatDescriptions = descriptions
        }

        var sampleRate: Double = AudioEngineConstants.defaultSampleRate
        var channels: Int = AudioEngineConstants.defaultChannels
        let bitrate: Int = AudioEngineConstants.defaultBitrate

        if let formatDescription = formatDescriptions.first {
            let audioStreamBasicDescription = CMAudioFormatDescriptionGetStreamBasicDescription(formatDescription)
            if let basicDescription = audioStreamBasicDescription?.pointee {
                sampleRate = basicDescription.mSampleRate
                channels = Int(basicDescription.mChannelsPerFrame)
            }
        }

        // Get file size and creation date for local files
        var fileSize: Int64 = 0
        var createdAt: TimeInterval = Date().timeIntervalSince1970
        var filename = ""
        var mimeType = "audio/m4a"

        if !uri.hasPrefix("http") {
            let fileURL: URL?
            if uri.hasPrefix("file://") {
                fileURL = URL(string: uri) // Safe unwrap instead of force unwrap
            } else {
                fileURL = URL(fileURLWithPath: uri)
            }

            guard let validFileURL = fileURL else {
                log("Invalid file URL: \(uri)")
                return [:]
            }

            if let attributes = try? FileManager.default.attributesOfItem(atPath: validFileURL.path) {
                fileSize = attributes[.size] as? Int64 ?? 0
                if let creationDate = attributes[.creationDate] as? Date {
                    createdAt = creationDate.timeIntervalSince1970
                }
            }

            filename = validFileURL.lastPathComponent

            // Determine MIME type based on file extension
            let pathExtension = validFileURL.pathExtension.lowercased()
            switch pathExtension {
            case "m4a":
                mimeType = AudioEngineConstants.mimeTypeM4A
            case "mp3":
                mimeType = "audio/mpeg"
            case "wav":
                mimeType = "audio/wav"
            case "aac":
                mimeType = "audio/aac"
            default:
                mimeType = AudioEngineConstants.mimeTypeM4A
            }
        } else {
            // For remote URLs, extract filename from URL
            if let url = URL(string: uri) {
                filename = url.lastPathComponent
                if filename.isEmpty {
                    filename = "remote_audio.m4a"
                }
            }
        }

        let durationInSeconds = CMTimeGetSeconds(duration)

        return [
            "path": uri,
            "webPath": uri,
            "uri": uri,
            "mimeType": mimeType,
            "size": fileSize,
            "duration": round(durationInSeconds * AudioEngineConstants.durationRoundingFactor) / AudioEngineConstants.durationRoundingFactor, // Round to 1 decimal place
            "sampleRate": sampleRate,
            "channels": channels,
            "bitrate": bitrate,
            "createdAt": Int64(createdAt * AudioEngineConstants.timestampMultiplier), // Convert to milliseconds
            "filename": filename
        ]
    }

    /**
     * Helper method to extract filename from URI
     */
    private func extractFilenameFromURI(_ uri: String) -> String {
        if let url = URL(string: uri) {
            let filename = url.lastPathComponent
            return filename.isEmpty ? "audio.m4a" : filename
        }
        return "audio.m4a"
    }

    // MARK: - RecordingManagerDelegate Implementation

    func recordingDidUpdateDuration(_ duration: Int) {
        notifyListeners("durationChange", data: ["duration": duration])
    }

    func recordingDidEncounterError(_ error: Error) {
        notifyListeners("error", data: ["message": error.localizedDescription])

        // Reject the pending stop recording call if there's an error
        if let call = pendingStopRecordingCall {
            call.reject("Recording error: \(error.localizedDescription)")
            pendingStopRecordingCall = nil
        }
    }

    func recordingDidFinish(_ info: [String: Any]) {
        // This will be called when recording finishes
        // The info contains the recording response
        log("Recording finished with info: \(info)")

        // Stop waveform data monitoring
        waveformDataManager.stopMonitoring()
        log("Waveform data monitoring stopped")

        // Reconfigure audio session for optimal playback after recording
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
            // Ensure session is active for potential immediate playback
            try audioSession.setActive(true)
            log("Audio session reconfigured for playback after recording")
        } catch {
            log("Warning: Failed to reconfigure audio session after recording: \(error.localizedDescription)")
        }

        // Resolve the pending stop recording call with the recording data
        if let call = pendingStopRecordingCall {
            call.resolve(info)
            pendingStopRecordingCall = nil
        }
    }

    func recordingDidChangeState(_ state: String, data: [String: Any]) {
        // Log the state change for debugging
        log("recordingDidChangeState called - state: \(state), data: \(data)")

        // Emit state change event to match Android behavior
        notifyListeners("recordingStateChange", data: [
            "state": state,
            "data": data
        ])

        log("recordingStateChange event emitted to JavaScript")
    }

    // MARK: - Playback Methods

    @objc func preloadTracks(_ call: CAPPluginCall) {
        guard let tracksArray = call.getArray("tracks") as? [String] else {
            call.reject("Invalid tracks array - expected array of URLs")
            return
        }

        print("CapacitorAudioEnginePlugin: preloadTracks called with \(tracksArray.count) tracks")
        for (index, url) in tracksArray.enumerated() {
            print("CapacitorAudioEnginePlugin: Track \(index): \(url)")
        }

        // Validate track URLs
        for (index, url) in tracksArray.enumerated() {
            if url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                print("CapacitorAudioEnginePlugin: Empty URL at index \(index)")
                call.reject("Invalid track URL at index \(index)")
                return
            }
        }

        let preloadNext = call.getBool("preloadNext") ?? true
        print("CapacitorAudioEnginePlugin: preloadNext = \(preloadNext)")

        do {
            print("CapacitorAudioEnginePlugin: Calling playbackManager.preloadTracks")
            let trackResults = try playbackManager.preloadTracks(trackUrls: tracksArray, preloadNext: preloadNext)
            print("CapacitorAudioEnginePlugin: preloadTracks completed successfully")

            // Return the track results in the same format as Android
            call.resolve([
                "tracks": trackResults
            ])
        } catch {
            print("CapacitorAudioEnginePlugin: preloadTracks failed with error: \(error)")
            print("CapacitorAudioEnginePlugin: Error details: \(error.localizedDescription)")
            call.reject("Failed to preload tracks: \(error.localizedDescription)")
        }
    }

    @objc func playAudio(_ call: CAPPluginCall) {
        do {
            if let url = call.getString("url") {
                // Play specific preloaded track by URL
                try playbackManager.playByUrl(url)
            } else {
                // Play current track
                try playbackManager.play()
            }
            call.resolve()
        } catch {
            call.reject("Failed to play audio: \(error.localizedDescription)")
        }
    }

    @objc func pauseAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Pause specific preloaded track by URL
            playbackManager.pauseByUrl(url)
        } else {
            // Pause current track
            playbackManager.pause()
        }
        call.resolve()
    }

    @objc func resumeAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Resume specific preloaded track by URL
            playbackManager.resumeByUrl(url)
        } else {
            // Resume current track
            playbackManager.resume()
        }
        call.resolve()
    }

    @objc func stopAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Stop specific preloaded track by URL
            playbackManager.stopByUrl(url)
        } else {
            // Stop current track
            playbackManager.stop()
        }
        call.resolve()
    }

    @objc func seekAudio(_ call: CAPPluginCall) {
        guard let seconds = call.getDouble("seconds") else {
            call.reject("Missing seconds parameter")
            return
        }

        if let url = call.getString("url") {
            // Seek in specific preloaded track by URL
            playbackManager.seekByUrl(url, to: seconds)
        } else {
            // Seek in current track
            playbackManager.seek(to: seconds)
        }
        call.resolve()
    }

    @objc func skipToNext(_ call: CAPPluginCall) {
        playbackManager.skipToNext()
        call.resolve()
    }

    @objc func skipToPrevious(_ call: CAPPluginCall) {
        playbackManager.skipToPrevious()
        call.resolve()
    }

    @objc func skipToIndex(_ call: CAPPluginCall) {
        guard let index = call.getInt("index") else {
            call.reject("Missing index parameter")
            return
        }

        playbackManager.skipToIndex(index)
        call.resolve()
    }

    @objc func getPlaybackInfo(_ call: CAPPluginCall) {
        let now = CACurrentMediaTime()

        // Return cached result if within cache interval
        if let cached = cachedPlaybackInfo,
           now - lastPlaybackInfoUpdate < playbackInfoCacheInterval {
            call.resolve(cached)
            return
        }

        // Generate fresh playback info
        var result: [String: Any] = [:]

        if let currentTrack = playbackManager.getCurrentTrack() {
            result["currentTrack"] = [
                "id": currentTrack.id,
                "url": currentTrack.url,
                "title": currentTrack.title ?? "",
                "artist": currentTrack.artist ?? "",
                "artworkUrl": currentTrack.artworkUrl ?? ""
            ]
        } else {
            result["currentTrack"] = NSNull()
        }

        result["currentIndex"] = playbackManager.getCurrentIndex()
        result["currentPosition"] = playbackManager.getCurrentPosition()
        result["duration"] = playbackManager.getDuration()
        result["isPlaying"] = playbackManager.isPlaying()
        result["status"] = statusToString(playbackManager.getStatus())

        // Cache the result
        cachedPlaybackInfo = result
        lastPlaybackInfoUpdate = now

        call.resolve(result)
    }

    @objc func openSettings(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            if let settingsUrl = URL(string: UIApplication.openSettingsURLString) {
                if UIApplication.shared.canOpenURL(settingsUrl) {
                    UIApplication.shared.open(settingsUrl) { success in
                        if success {
                            call.resolve()
                        } else {
                            call.reject("Failed to open settings")
                        }
                    }
                } else {
                    call.reject("Cannot open settings URL")
                }
            } else {
                call.reject("Invalid settings URL")
            }
        }
    }

    private func statusToString(_ status: PlaybackStatus) -> String {
        switch status {
        case .idle: return "idle"
        case .loading: return "loading"
        case .playing: return "playing"
        case .paused: return "paused"
        case .stopped: return "stopped"
        }
    }

    // MARK: - PlaybackManagerDelegate Implementation

    func playbackManager(_ manager: PlaybackManager, trackDidChange track: AudioTrack, at index: Int) {
        invalidatePlaybackInfoCache()

        let data: [String: Any] = [
            "track": [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ],
            "index": index
        ]
        notifyListeners("trackChanged", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, trackDidEnd track: AudioTrack, at index: Int) {
        invalidatePlaybackInfoCache()

        let data: [String: Any] = [
            "track": [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ],
            "index": index
        ]
        notifyListeners("trackEnded", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackDidStart track: AudioTrack, at index: Int) {
        invalidatePlaybackInfoCache()

        let data: [String: Any] = [
            "track": [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ],
            "index": index
        ]
        notifyListeners("playbackStarted", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackDidPause track: AudioTrack, at index: Int) {
        invalidatePlaybackInfoCache()

        let data: [String: Any] = [
            "track": [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ],
            "index": index,
            "position": manager.getCurrentPosition()
        ]
        notifyListeners("playbackPaused", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackDidFail error: Error) {
        notifyListeners("playbackError", data: ["message": error.localizedDescription])
    }

    func playbackManager(_ manager: PlaybackManager, playbackProgress track: AudioTrack, at index: Int, currentPosition: Double, duration: Double, isPlaying: Bool) {
        // Only send progress events, don't invalidate cache as these are frequent updates
        // Cache invalidation is handled by state change events
        let data: [String: Any] = [
            "track": [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ],
            "index": index,
            "currentPosition": currentPosition,
            "duration": duration,
            "isPlaying": isPlaying
        ]
        notifyListeners("playbackProgress", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, statusChanged track: AudioTrack?, at index: Int, status: PlaybackStatus, currentPosition: Double, duration: Double, isPlaying: Bool) {
        invalidatePlaybackInfoCache()

        var data: [String: Any] = [
            "index": index,
            "status": statusToString(status),
            "currentPosition": currentPosition,
            "duration": duration,
            "isPlaying": isPlaying
        ]

        if let track = track {
            data["currentTrack"] = [
                "id": track.id,
                "url": track.url,
                "title": track.title ?? "",
                "artist": track.artist ?? "",
                "artworkUrl": track.artworkUrl ?? ""
            ]
        } else {
            data["currentTrack"] = NSNull()
        }

        notifyListeners("playbackStatusChanged", data: data)
    }

    // MARK: - WaveformEventCallback Implementation

    func notifyListeners(_ eventName: String, data: [String: Any]) {
        super.notifyListeners(eventName, data: data)
    }

}
