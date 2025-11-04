import Foundation
import Capacitor
@preconcurrency import AVFoundation

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */

@objc(CapacitorAudioEnginePlugin)
public class CapacitorAudioEnginePlugin: CAPPlugin, CAPBridgedPlugin, WaveLevelEventCallback, RecordingManagerDelegate, PlaybackManagerDelegate {
    public let identifier = "CapacitorAudioEnginePlugin"
    public let jsName = "CapacitorAudioEngine"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissionMicrophone", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissionNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissionMicrophone", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissionNotifications", returnType: CAPPluginReturnPromise),

        CAPPluginMethod(name: "configureWaveform", returnType: CAPPluginReturnPromise),

        CAPPluginMethod(name: "destroyWaveform", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addListener", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "removeAllListeners", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAudioInfo", returnType: CAPPluginReturnPromise),
        // Playback methods
        CAPPluginMethod(name: "preloadTracks", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "playTrack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseTrack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeTrack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopTrack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "seekTrack", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToNext", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToPrevious", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "skipToIndex", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPlaybackInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyPlayback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "openSettings", returnType: CAPPluginReturnPromise),
        // Recording methods
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resetRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRecordingStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "trimAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "micAvailable", returnType: CAPPluginReturnPromise),
    ]

    // MARK: - Properties

    private var waveLevelEmitter: WaveLevelEmitter!
    private var permissionService: PermissionManagerService!
    private var recordingManager: RecordingManager!
    private var playbackManager: PlaybackManager!

    // MARK: - Thread Safety

    private let stateQueue = DispatchQueue(label: "audio-engine-state", qos: .userInteractive)

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    // MARK: - Lifecycle

    public override func load() {
        super.load()
        permissionService = PermissionManagerService()

        // Initialize wave level emitter with event callback
        waveLevelEmitter = WaveLevelEmitter(eventCallback: self)

        // Initialize recording manager
        recordingManager = RecordingManager(delegate: self)

        // Initialize playback manager
        playbackManager = PlaybackManager(delegate: self)
    }

    // MARK: - Logging Utility

    private func log(_ message: String) {
        #if DEBUG
        print("[AudioEngine] \(message)")
        #endif
    }

    // MARK: - Path Utilities

    private func getRelativePathForDirectoryData(absolutePath: String) -> String {
        // Get the Application Support directory (equivalent to Directory.Data)
        guard let appSupportDir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return absolutePath
        }

        let appSupportPath = appSupportDir.path

        #if DEBUG
        print("[AudioEngine] getRelativePathForDirectoryData - input: '\(absolutePath)'")
        print("[AudioEngine] getRelativePathForDirectoryData - appSupportPath: '\(appSupportPath)'")
        print("[AudioEngine] getRelativePathForDirectoryData - hasPrefix check: \(absolutePath.hasPrefix(appSupportPath))")
        print("[AudioEngine] getRelativePathForDirectoryData - input length: \(absolutePath.count)")
        print("[AudioEngine] getRelativePathForDirectoryData - appSupportPath length: \(appSupportPath.count)")
        #endif

        // If the absolute path starts with the app support directory, return the relative part
        if absolutePath.hasPrefix(appSupportPath) {
            let relativePath = String(absolutePath.dropFirst(appSupportPath.count))
            let result = relativePath.hasPrefix("/") ? String(relativePath.dropFirst()) : relativePath
            #if DEBUG
            print("[AudioEngine] getRelativePathForDirectoryData - case 1 (absolute): \(result)")
            #endif
            return result
        }

        // If the path starts with "/" but doesn't start with app support directory,
        // it's a relative path that should be returned as-is (just remove leading slash)
        if absolutePath.hasPrefix("/") {
            let result = String(absolutePath.dropFirst())
            #if DEBUG
            print("[AudioEngine] getRelativePathForDirectoryData - case 2 (relative with slash): \(result)")
            #endif
            return result
        }

        // Otherwise, return the path as-is (it's already relative)
        #if DEBUG
        print("[AudioEngine] getRelativePathForDirectoryData - case 3 (already relative): \(absolutePath)")
        #endif
        return absolutePath
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

    @objc public override func checkPermissions(_ call: CAPPluginCall) {
        Task {
            let result = await permissionService.checkPermissions()
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    @objc func checkPermissionMicrophone(_ call: CAPPluginCall) {
        Task {
            let result = await permissionService.checkPermissionMicrophone()
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    @objc func checkPermissionNotifications(_ call: CAPPluginCall) {
        Task {
            let result = await permissionService.checkPermissionNotifications()
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    @objc public override func requestPermissions(_ call: CAPPluginCall) {
        Task {
            let options = call.getObject("options")
            let result = await permissionService.requestPermissions(options: options)
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    @objc func requestPermissionMicrophone(_ call: CAPPluginCall) {
        Task {
            let options = call.getObject("options")
            let result = await permissionService.requestPermissionMicrophone(options: options)
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    @objc func requestPermissionNotifications(_ call: CAPPluginCall) {
        Task {
            let options = call.getObject("options")
            let result = await permissionService.requestPermissionNotifications(options: options)
            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    // MARK: - Recording APIs removed (streaming is the supported path on iOS)

    @objc func configureWaveform(_ call: CAPPluginCall) {
        // Accept 'EmissionInterval' in ms from the call, fallback to 1000ms if not provided
        let intervalMs = call.getInt("EmissionInterval") ?? 1000
        waveLevelEmitter.setEmissionInterval(intervalMs)
        // Keep recording wave interval in sync
        recordingManager.setWaveLevelEmissionInterval(intervalMs)

        log("Wave level emitter configured - interval: \(intervalMs)ms")

        // Build simplified result
        let configuration: [String: Any] = [
            "emissionInterval": intervalMs
        ]

        let result: [String: Any] = [
            "success": true,
            "configuration": configuration
        ]
        call.resolve(result)

        // Start monitoring if not already active
        if !waveLevelEmitter.isMonitoring() {
            waveLevelEmitter.startMonitoring()
            log("Wave level monitoring started via configuration")
        }
    }

    @objc func destroyWaveform(_ call: CAPPluginCall) {
        // Stop monitoring if active
        if waveLevelEmitter.isMonitoring() {
            log("Stopping wave level monitoring before destruction")
        }

        // Cleanup wave level resources
        waveLevelEmitter.cleanup()
        log("Wave level configuration destroyed and resources cleaned up")

        call.resolve()
    }

    @objc func getAudioInfo(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        Task {
            do {
                // Check if it's a remote URL or local file
                if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
                    // For remote URLs, use the old extractAudioInfo method
                    let audioInfo = try await extractAudioInfo(from: uri)
                    call.resolve(audioInfo)
                } else {
                    // For local files, use the new shared method
                    let audioInfo = try await createAudioFileInfo(filePath: uri)
                    call.resolve(audioInfo)
                }
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
                if let url = URL(string: uri) {
                    fileURL = url
                } else {
                    let pathString = String(uri.dropFirst(7))
                    fileURL = URL(fileURLWithPath: pathString)
                }
            } else {
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

    /**
     * Create AudioFileInfo response with proper path formatting to match Android
     * - path: absolute file path
     * - uri: file:// URI format
     * - webPath: capacitor://localhost/_capacitor_file_ format
     */
    private func createAudioFileInfo(filePath: String) async throws -> [String: Any] {
        let fileURL: URL
        if filePath.hasPrefix("file://") {
            if let url = URL(string: filePath) {
                fileURL = url
            } else {
                let pathString = String(filePath.dropFirst(7))
                fileURL = URL(fileURLWithPath: pathString)
            }
        } else {
            fileURL = URL(fileURLWithPath: filePath)
        }

        // Check if file exists
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "File does not exist"])
        }

        // Get file attributes
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        let fileSize = attributes[.size] as? Int64 ?? 0
        let createdAt: TimeInterval
        if let creationDate = attributes[.creationDate] as? Date {
            createdAt = creationDate.timeIntervalSince1970
        } else {
            createdAt = Date().timeIntervalSince1970
        }

        let filename = fileURL.lastPathComponent
        let absolutePath = fileURL.path
        let relativePath = getRelativePathForDirectoryData(absolutePath: absolutePath)

        // Determine MIME type based on file extension
        let pathExtension = fileURL.pathExtension.lowercased()
        let mimeType: String
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

        // Load audio asset
        let asset = AVAsset(url: fileURL)
        let duration: CMTime
        let tracks: [AVAssetTrack]

        if #available(iOS 15.0, *) {
            duration = try await asset.load(.duration)
            tracks = try await asset.load(.tracks)
        } else {
            duration = asset.duration
            tracks = asset.tracks
        }

        // Find the audio track
        var audioTrack: AVAssetTrack?
        if #available(iOS 15.0, *) {
            for track in tracks {
                let mediaType = track.mediaType
                if mediaType == AVMediaType.audio {
                    audioTrack = track
                    break
                }
            }
        } else {
            audioTrack = tracks.first { track in
                return track.mediaType == AVMediaType.audio
            }
        }

        guard let audioTrack = audioTrack else {
            throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "No audio track found"])
        }

        // Load audio track properties
        var formatDescriptions: [CMFormatDescription]
        if #available(iOS 15.0, *) {
            formatDescriptions = try await audioTrack.load(.formatDescriptions)
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

        let durationInSeconds = CMTimeGetSeconds(duration)

        let encodedFileURL = URL(fileURLWithPath: absolutePath)
        let encodedURI = encodedFileURL.absoluteString

        return [
            "path": relativePath,
            "filename": filename,
            "size": fileSize,
            "createdAt": Int64(createdAt * AudioEngineConstants.timestampMultiplier),
            "uri": encodedURI,
            "webPath": "capacitor://localhost/_capacitor_file_" + absolutePath,
            "duration": round(durationInSeconds * AudioEngineConstants.durationRoundingFactor) / AudioEngineConstants.durationRoundingFactor,
            "mimeType": mimeType,
            "bitrate": bitrate,
            "channels": channels,
            "sampleRate": sampleRate
        ]
    }

    // MARK: - Recording delegate removed

    // MARK: - Playback Methods

    @objc func preloadTracks(_ call: CAPPluginCall) {
        guard let tracksArray = call.getArray("tracks", String.self) else {
            call.reject("INVALID_PARAMETERS", "Missing required parameter: tracks")
            return
        }

        if tracksArray.isEmpty {
            call.reject("INVALID_PARAMETERS", "No valid tracks provided")
            return
        }

        var results: [[String: Any]] = []
        let group = DispatchGroup()

        for url in tracksArray {
            group.enter()
            playbackManager.preloadTrack(url: url) { result in
                switch result {
                case .success(let trackData):
                    let trackResult: [String: Any] = [
                        "url": trackData.url,
                        "loaded": true,
                        "mimeType": trackData.mimeType,
                        "duration": trackData.duration,
                        "size": trackData.size
                    ]
                    results.append(trackResult)

                case .failure(let error):
                    let trackResult: [String: Any] = [
                        "url": url,
                        "loaded": false,
                        "error": error.localizedDescription
                    ]
                    results.append(trackResult)
                }
                group.leave()
            }
        }

        group.notify(queue: .main) {
            call.resolve(["tracks": results])
        }
    }

    @objc func playTrack(_ call: CAPPluginCall) {
        let url = call.getString("url")
        playbackManager.playTrack(url: url)
        call.resolve()
    }

    @objc func pauseTrack(_ call: CAPPluginCall) {
        let url = call.getString("url")
        playbackManager.pauseTrack(url: url)
        call.resolve()
    }

    @objc func resumeTrack(_ call: CAPPluginCall) {
        let url = call.getString("url")
        playbackManager.resumeTrack(url: url)
        call.resolve()
    }

    @objc func stopTrack(_ call: CAPPluginCall) {
        let url = call.getString("url")
        playbackManager.stopTrack(url: url)
        call.resolve()
    }

    @objc func seekTrack(_ call: CAPPluginCall) {
        guard let seconds = call.getInt("seconds") else {
            call.reject("INVALID_PARAMETERS", "Missing required parameter: seconds")
            return
        }

        let url = call.getString("url")
        playbackManager.seekTrack(seconds: seconds, url: url)
        call.resolve()
    }

    @objc func skipToNext(_ call: CAPPluginCall) {
        // Simplified - no-op for single track playback
        call.resolve()
    }

    @objc func skipToPrevious(_ call: CAPPluginCall) {
        // Simplified - no-op for single track playback
        call.resolve()
    }

    @objc func skipToIndex(_ call: CAPPluginCall) {
        // Simplified - no-op for single track playback
        call.resolve()
    }

    @objc func getPlaybackInfo(_ call: CAPPluginCall) {
        let info = playbackManager.getPlaybackInfo()

        var result: [String: Any] = [
            "currentIndex": info.currentIndex,
            "currentPosition": info.currentPosition,
            "duration": info.duration,
            "isPlaying": info.isPlaying
        ]

        if let trackId = info.trackId, let url = info.url {
            result["currentTrack"] = [
                "id": trackId,
                "url": url
            ]
        } else {
            result["currentTrack"] = NSNull()
        }

        call.resolve(result)
    }

    @objc func destroyPlayback(_ call: CAPPluginCall) {
        playbackManager.destroy()
        // Reinitialize with same delegate
        playbackManager = PlaybackManager(delegate: self)
        call.resolve()
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

    // MARK: - WaveformEventCallback Implementation

    func notifyListeners(_ eventName: String, data: [String: Any]) {
        super.notifyListeners(eventName, data: data)
    }

    // MARK: - Recording Methods

    @objc func startRecording(_ call: CAPPluginCall) {
        do {
            try permissionService.validateRecordingPermissions()
        } catch {
            call.reject("Permission Error", error.localizedDescription)
            return
        }
        guard let path = call.getString("path"), !path.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            call.reject("Path is required for startRecording")
            return
        }
        // If no path provided, RecordingManager will use Directory.Data equivalent
        // (app's Application Support directory)
        recordingManager.configureRecording(encoding: nil, bitrate: nil, path: path)
        recordingManager.startRecording()
        // Start shared wave monitoring when recording starts
        waveLevelEmitter.startMonitoring()

        // Get the recording status to retrieve the file path/URI
        let status = recordingManager.getStatus()

        guard let filePath = status.path else {
            call.reject("No recording file path available")
            return
        }

        // Return the URI
        call.resolve([
            "uri": "file://" + filePath
        ])
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        recordingManager.stopRecording()
        // Stop shared wave monitoring when recording stops
        waveLevelEmitter.stopMonitoring()

        // Get the recording status to retrieve the file path
        let status = recordingManager.getStatus()

        guard let filePath = status.path else {
            call.reject("No recording file path available")
            return
        }

        // Extract audio file info using shared method
        Task {
            do {
                let audioInfo = try await createAudioFileInfo(filePath: filePath)
                call.resolve(audioInfo)
            } catch {
                call.reject("Failed to get audio info: \(error.localizedDescription)")
            }
        }
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        recordingManager.pauseRecording()
        // Pause shared wave monitoring
        waveLevelEmitter.pauseMonitoring()
        call.resolve()
    }

    @objc func resumeRecording(_ call: CAPPluginCall) {
        recordingManager.resumeRecording()
        // Resume shared wave monitoring
        waveLevelEmitter.resumeMonitoring()
        call.resolve()
    }

    @objc func resetRecording(_ call: CAPPluginCall) {
        recordingManager.resetRecording()
        // Pause shared wave monitoring; let WaveLevelEmitter clear via pause
        waveLevelEmitter.pauseMonitoring()
        call.resolve()
    }

    @objc func getRecordingStatus(_ call: CAPPluginCall) {
        let status = recordingManager.getStatus()
        var result: [String: Any] = [
            "status": status.status,
            "duration": status.duration
        ]
        if let path = status.path {
            result["path"] = path
        }
        call.resolve(result)
    }

    @objc func trimAudio(_ call: CAPPluginCall) {
        guard let uriString = call.getString("uri") else {
            call.reject("Missing required parameter: uri")
            return
        }

        guard let startTime = call.getDouble("startTime") else {
            call.reject("Missing required parameter: startTime")
            return
        }

        guard let endTime = call.getDouble("endTime") else {
            call.reject("Missing required parameter: endTime")
            return
        }

        // Validate time range
        if startTime < 0 || endTime <= startTime {
            call.reject("Invalid time range: startTime must be >= 0 and endTime must be > startTime")
            return
        }

        // Convert URI to URL
        let sourceURL: URL
        if uriString.hasPrefix("file://") {
            if let url = URL(string: uriString) {
                sourceURL = url
            } else {
                let pathString = String(uriString.dropFirst(7))
                sourceURL = URL(fileURLWithPath: pathString)
            }
        } else if uriString.hasPrefix("/") {
            sourceURL = URL(fileURLWithPath: uriString)
        } else {
            // Relative path - use app's documents directory
            guard let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
                call.reject("Failed to access documents directory")
                return
            }
            sourceURL = documentsPath.appendingPathComponent(uriString)
        }

        // Check if file exists
        let sourceExists = FileManager.default.fileExists(atPath: sourceURL.path)
        guard sourceExists else {
            call.reject("Source file does not exist: \(sourceURL.path)")
            return
        }

        // Create output path in the same directory as the source file
        let sourceDirectory = sourceURL.deletingLastPathComponent()
        let originalFileName = sourceURL.lastPathComponent
        // Create temporary name first to avoid conflicts during processing
        let tempOutputFileName = "temp_trimming_\(Int(Date().timeIntervalSince1970 * 1000)).\(sourceURL.pathExtension)"
        let outputURL = sourceDirectory.appendingPathComponent(tempOutputFileName)

        // Create asset from source file
        let asset = AVAsset(url: sourceURL)

        // Validate that the trim range is within asset duration
        let assetDuration = CMTimeGetSeconds(asset.duration)
        if endTime > assetDuration {
            call.reject("End time (\(endTime)s) exceeds audio duration (\(assetDuration)s)")
            return
        }

        // Create export session
        guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
            call.reject("Failed to create export session")
            return
        }

        exportSession.outputURL = outputURL
        exportSession.outputFileType = .m4a

        // Set time range for trimming
        let startCMTime = CMTime(seconds: startTime, preferredTimescale: 600)
        let endCMTime = CMTime(seconds: endTime, preferredTimescale: 600)
        let timeRange = CMTimeRange(start: startCMTime, end: endCMTime)
        exportSession.timeRange = timeRange

        // Perform export
        exportSession.exportAsynchronously { [weak exportSession] in
            guard let exportSession = exportSession else {
                DispatchQueue.main.async {
                    call.reject("Export session was deallocated")
                }
                return
            }

            let status = exportSession.status
            let error = exportSession.error

            DispatchQueue.main.async {
                switch status {
                case .completed:
                    // Extract audio file info for the trimmed file
                    Task {
                        do {
                            // Delete the original file first (if it exists)
                            let originalExists = FileManager.default.fileExists(atPath: sourceURL.path)

                            if originalExists {
                                do {
                                    try FileManager.default.removeItem(at: sourceURL)
                                } catch {
                                    // Ignore error if file cannot be removed
                                    print("Warning: Could not remove original file: \(error.localizedDescription)")
                                }
                            }

                            // Rename the temporary trimmed file to the original filename
                            let finalURL = sourceDirectory.appendingPathComponent(originalFileName)
                            var actualFileURL = finalURL

                            // Try to rename temp file to original filename
                            if FileManager.default.fileExists(atPath: outputURL.path) {
                                do {
                                    try FileManager.default.moveItem(at: outputURL, to: finalURL)

                                    // Verify the moved file exists
                                    FileManager.default.fileExists(atPath: finalURL.path)
                                } catch {
                                    // Use temp file if rename fails
                                    actualFileURL = outputURL
                                }
                            } else {
                                actualFileURL = outputURL
                            }

                            // Verify the actual file exists before getting info
                            let finalFileExists = FileManager.default.fileExists(atPath: actualFileURL.path)

                            guard finalFileExists else {
                                throw NSError(domain: "CapacitorAudioEngine", code: -1,
                                            userInfo: [NSLocalizedDescriptionKey: "Trimmed file not found at: \(actualFileURL.path)"])
                            }

                            let audioInfo = try await self.createAudioFileInfo(filePath: actualFileURL.path)
                            call.resolve(audioInfo)
                        } catch {
                            call.reject("Export completed but failed to get audio info: \(error.localizedDescription)")
                        }
                    }

                case .failed:
                    if let error = error {
                        call.reject("Export failed: \(error.localizedDescription)")
                    } else {
                        call.reject("Export failed with unknown error")
                    }

                case .cancelled:
                    call.reject("Export was cancelled")

                default:
                    call.reject("Export failed with status: \(status.rawValue)")
                }
            }
        }
    }


    @objc func micAvailable(_ call: CAPPluginCall) {
        Task {
            let audioSession = AVAudioSession.sharedInstance()

            // Try to activate the audio session to get input availability
            try? audioSession.setCategory(.record, mode: .default)

            // Check if microphone exists
            let microphoneExists: Bool
            if let availableInputs = audioSession.availableInputs {
                microphoneExists = !availableInputs.isEmpty
            } else {
                microphoneExists = audioSession.isInputAvailable
            }

            // Check if microphone is currently in use for recording
            // We specifically test the record category since that's what we use for recording
            let microphoneInUse = microphoneExists ? isMicrophoneInUse(audioSession: audioSession) : false

            // isAvailable is true only if MIC exists AND is not in use by another app
            let isAvailable = microphoneExists && !microphoneInUse

            let result: [String: Any] = [
                "isAvailable": isAvailable
            ]

            await MainActor.run {
                call.resolve(result)
            }
        }
    }

    private func isMicrophoneInUse(audioSession: AVAudioSession) -> Bool {
        // Check if the MIC is currently in use for recording by another app
        do {
            // Set category to record - same as we use for actual recording
            try audioSession.setCategory(.record, mode: .default, options: [])

            // Try to activate the audio session for recording
            try audioSession.setActive(true, options: [])

            // Check if input is available
            let isInputAvailable = audioSession.isInputAvailable

            if !isInputAvailable {
                try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
                print("[AudioEngine] Input not available - MIC might be in use")
                return true
            }

            // Try to get the input and check if we can actually use it
            // by attempting to set it as the preferred input
            if let currentRoute = audioSession.currentRoute.inputs.first {
                print("[AudioEngine] Current audio input: \(currentRoute.portName)")

                // Try to set input gain if supported (this will fail if mic is locked by another app)
                if audioSession.isInputGainSettable {
                    let currentGain = audioSession.inputGain
                    // Try to set the same gain to test if we have access
                    do {
                        try audioSession.setInputGain(currentGain)
                        print("[AudioEngine] Successfully tested input gain - MIC is available")
                    } catch {
                        try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
                        print("[AudioEngine] Cannot set input gain - MIC might be in use: \(error)")
                        return true
                    }
                }
            }

            // Try to create a temporary audio engine to test if we can actually record
            let audioEngine = AVAudioEngine()
            let inputNode = audioEngine.inputNode

            // Get the input format
            let inputFormat = inputNode.inputFormat(forBus: 0)

            if inputFormat.sampleRate == 0 || inputFormat.channelCount == 0 {
                try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)
                print("[AudioEngine] Invalid input format - MIC might be in use")
                return true
            }

            // Try to install a tap to verify we can access the input
            inputNode.installTap(onBus: 0, bufferSize: 1024, format: inputFormat) { (buffer, time) in
                // We don't need to do anything with the data
            }
            // Remove the tap immediately
            inputNode.removeTap(onBus: 0)

            // Deactivate the session
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)

            print("[AudioEngine] MIC is available for recording")
            return false

        } catch let error as NSError {
            try? audioSession.setActive(false, options: .notifyOthersOnDeactivation)

            // Check for specific error codes that indicate mic is in use
            if error.domain == NSOSStatusErrorDomain {
                // Error codes that indicate resource is busy
                // AVAudioSessionErrorCodeResourceNotAvailable = 561017449 ('!res')
                // AVAudioSessionErrorCodeInsufficientPriority = 561015905 ('!pri')
                if error.code == 561017449 || error.code == 561015905 {
                    print("[AudioEngine] MIC resource not available or insufficient priority - in use by another app")
                    return true
                }
            }

            print("[AudioEngine] Cannot activate audio session for recording - MIC might be in use: \(error)")
            return true
        }
    }

    // MARK: - RecordingManagerDelegate
    func recordingDidChangeStatus(_ status: String) {
        notifyListeners("recordingStatusChanged", data: ["status": status])
    }

    func recordingDidEncounterError(_ error: Error) {
        notifyListeners("error", data: ["message": error.localizedDescription])
    }


    func recordingDidUpdateDuration(_ duration: Double) {
        notifyListeners("durationChange", data: ["duration": duration])
    }

    func recordingDidEmitWaveLevel(_ level: Double, timestamp: TimeInterval) {
        notifyListeners("waveLevel", data: ["level": level, "timestamp": Int(timestamp * 1000)])
    }

    func recordingDidFinalize(_ path: String) {
        notifyListeners("recordingFinalized", data: [
            "status": "finalized",
            "path": path,
            "uri": path,
            "webPath": "capacitor://localhost/_capacitor_file_" + path
        ])
    }

    // MARK: - PlaybackManagerDelegate

    func playbackDidChangeStatus(_ status: String, url: String, position: Int) {
        notifyListeners("playbackStatusChanged", data: [
            "status": status,
            "url": url,
            "position": position
        ])
    }

    func playbackDidEncounterError(_ trackId: String, message: String) {
        notifyListeners("playbackError", data: [
            "trackId": trackId,
            "message": message
        ])
    }

    func playbackDidUpdateProgress(_ trackId: String, url: String, currentPosition: Int, duration: Int, isPlaying: Bool) {
        notifyListeners("playbackProgress", data: [
            "trackId": trackId,
            "url": url,
            "currentPosition": currentPosition,
            "duration": duration,
            "isPlaying": isPlaying
        ])
    }

    func playbackDidComplete(_ trackId: String, url: String) {
        notifyListeners("playbackCompleted", data: [
            "trackId": trackId,
            "url": url
        ])
    }

}
