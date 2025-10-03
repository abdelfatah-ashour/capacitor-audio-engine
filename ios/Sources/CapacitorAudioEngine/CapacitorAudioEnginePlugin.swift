import Foundation
import Capacitor
@preconcurrency import AVFoundation

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */

@objc(CapacitorAudioEnginePlugin)
public class CapacitorAudioEnginePlugin: CAPPlugin, CAPBridgedPlugin, PlaybackManagerDelegate, WaveLevelEventCallback, RecordingManagerDelegate {
    public let identifier = "CapacitorAudioEnginePlugin"
    public let jsName = "CapacitorAudioEngine"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "checkPermissions", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissionMicrophone", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "checkPermissionNotifications", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "requestPermissions", returnType: CAPPluginReturnPromise),

        CAPPluginMethod(name: "configureWaveform", returnType: CAPPluginReturnPromise),

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
        // Recording methods
        CAPPluginMethod(name: "startRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pauseRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumeRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resetRecording", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getRecordingStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "trimAudio", returnType: CAPPluginReturnPromise),
    ]

    // MARK: - Properties

    private var playbackManager: PlaybackManager!
    private var waveLevelEmitter: WaveLevelEmitter!
    private var permissionService: PermissionManagerService!
    private var recordingManager: RecordingManager!

    // MARK: - Thread Safety

    private let stateQueue = DispatchQueue(label: "audio-engine-state", qos: .userInteractive)

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    // MARK: - URL to Track ID Mapping (delegated to PlaybackManager)
    private func findTrackIdByUrl(_ url: String) -> String? {
        return playbackManager.getTrackId(for: url)
    }

    // MARK: - Lifecycle

    public override func load() {
        super.load()
        permissionService = PermissionManagerService()

        // Initialize playback manager and set delegate
        playbackManager = PlaybackManager()
        playbackManager.delegate = self

        // Initialize wave level emitter with event callback
        waveLevelEmitter = WaveLevelEmitter(eventCallback: self)

        // Initialize recording manager
        recordingManager = RecordingManager(delegate: self)
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

    /**
     * Create AudioFileInfo response with proper path formatting to match Android
     * - path: absolute file path
     * - uri: file:// URI format
     * - webPath: capacitor://localhost/_capacitor_file_ format
     */
    private func createAudioFileInfo(filePath: String) async throws -> [String: Any] {
        let fileURL: URL
        if filePath.hasPrefix("file://") {
            guard let url = URL(string: filePath) else {
                throw NSError(domain: "CapacitorAudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid file URI"])
            }
            fileURL = url
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

        // Format paths to match Android:
        // - path: absolute path
        // - uri: file:// format
        // - webPath: capacitor://localhost/_capacitor_file_ format
        return [
            "path": absolutePath,
            "filename": filename,
            "size": fileSize,
            "createdAt": Int64(createdAt * AudioEngineConstants.timestampMultiplier),
            "uri": "file://" + absolutePath,
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

        do {
            print("CapacitorAudioEnginePlugin: Calling playbackManager.preloadTracks")
            let trackResults = try playbackManager.preloadTracks(trackUrls: tracksArray)
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
        if let url = call.getString("url") {
            // Play specific preloaded track by URL
            playbackManager.play(identifier: url)
        } else {
            // Play current track
            playbackManager.play(identifier: nil)
        }
        call.resolve()
    }

    @objc func pauseAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Pause specific preloaded track by URL
            playbackManager.pause(identifier: url)
        } else {
            // Pause current track
            playbackManager.pause(identifier: nil)
        }
        call.resolve()
    }

    @objc func resumeAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Resume specific preloaded track by URL
            playbackManager.play(identifier: url) // Resume is handled by play
        } else {
            // Resume current track
            playbackManager.play(identifier: nil)
        }
        call.resolve()
    }

    @objc func stopAudio(_ call: CAPPluginCall) {
        if let url = call.getString("url") {
            // Stop specific preloaded track by URL
            playbackManager.stop(identifier: url)
        } else {
            // Stop current track
            playbackManager.stop(identifier: nil)
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
            playbackManager.seek(identifier: url, to: seconds)
        } else {
            // Seek in current track
            playbackManager.seek(identifier: nil, to: seconds)
        }
        call.resolve()
    }

    @objc func skipToNext(_ call: CAPPluginCall) {
        // Playlist functionality removed - simplified to single track playback
        call.resolve()
    }

    @objc func skipToPrevious(_ call: CAPPluginCall) {
        // Playlist functionality removed - simplified to single track playback
        call.resolve()
    }

    @objc func skipToIndex(_ call: CAPPluginCall) {
        // Playlist functionality removed - simplified to single track playback
        call.resolve()
    }

    @objc func getPlaybackInfo(_ call: CAPPluginCall) {
        var result: [String: Any] = [:]

        if let currentTrackId = playbackManager.getCurrentTrackId() {
            // Get the URL for the current track
            let currentUrl = playbackManager.getCurrentTrackUrl() ?? ""

            result["currentTrack"] = [
                "id": currentTrackId,
                "url": currentUrl
            ]
            result["currentIndex"] = 0 // Single track playback
            result["currentPosition"] = playbackManager.getCurrentPosition(identifier: currentTrackId)
            result["duration"] = playbackManager.getDuration(identifier: currentTrackId)
            result["isPlaying"] = playbackManager.isPlaying(identifier: currentTrackId)
        } else {
            result["currentTrack"] = NSNull()
            result["currentIndex"] = -1
            result["currentPosition"] = 0.0
            result["duration"] = 0.0
            result["isPlaying"] = false
        }

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


    // MARK: - PlaybackManagerDelegate Implementation (Simplified)

    func playbackManager(_ manager: PlaybackManager, playbackStarted trackId: String) {
        let currentUrl = manager.getUrl(for: trackId) ?? ""
        let data: [String: Any] = [
            "trackId": trackId,
            "url": currentUrl
        ]
        notifyListeners("playbackStarted", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackPaused trackId: String) {
        let currentUrl = manager.getUrl(for: trackId) ?? ""
        let data: [String: Any] = [
            "trackId": trackId,
            "url": currentUrl,
            "position": manager.getCurrentPosition(identifier: trackId)
        ]
        notifyListeners("playbackPaused", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackStopped trackId: String) {
        let currentUrl = manager.getUrl(for: trackId) ?? ""
        let data: [String: Any] = [
            "trackId": trackId,
            "url": currentUrl
        ]
        notifyListeners("playbackStopped", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackError trackId: String, error: Error) {
        let data: [String: Any] = [
            "trackId": trackId,
            "message": error.localizedDescription
        ]
        notifyListeners("playbackError", data: data)
    }

    func playbackManager(_ manager: PlaybackManager, playbackProgress trackId: String, currentPosition: Double, duration: Double) {
        let currentUrl = manager.getUrl(for: trackId) ?? ""
        let data: [String: Any] = [
            "trackId": trackId,
            "url": currentUrl,
            "currentPosition": currentPosition,
            "duration": duration,
            "isPlaying": manager.isPlaying(identifier: trackId)
        ]
        notifyListeners("playbackProgress", data: data)
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

        call.resolve()
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
            sourceURL = URL(string: uriString)!
        } else if uriString.hasPrefix("/") {
            sourceURL = URL(fileURLWithPath: uriString)
        } else {
            // Relative path - use app's documents directory
            let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
            sourceURL = documentsPath.appendingPathComponent(uriString)
        }

        // Check if file exists
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
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
        exportSession.exportAsynchronously {
            DispatchQueue.main.async {
                switch exportSession.status {
                case .completed:
                    // Extract audio file info for the trimmed file
                    Task {
                        do {
                            // Delete the original file first
                            do {
                                try FileManager.default.removeItem(at: sourceURL)
                                self.log("Deleted original file: \(sourceURL.path)")
                            } catch {
                                self.log("Warning: Could not delete original file: \(error.localizedDescription)")
                                // Don't fail the operation if deletion fails
                            }

                            // Rename the temporary trimmed file to the original filename
                            let finalURL = sourceDirectory.appendingPathComponent(originalFileName)
                            do {
                                try FileManager.default.moveItem(at: outputURL, to: finalURL)
                                self.log("Renamed trimmed file to original name: \(finalURL.path)")
                            } catch {
                                self.log("Warning: Could not rename trimmed file: \(error.localizedDescription)")
                                // Use the temp file path if rename fails
                            }

                            let audioInfo = try await self.createAudioFileInfo(filePath: finalURL.path)
                            call.resolve(audioInfo)
                        } catch {
                            call.reject("Export completed but failed to get audio info: \(error.localizedDescription)")
                        }
                    }

                case .failed:
                    if let error = exportSession.error {
                        call.reject("Export failed: \(error.localizedDescription)")
                    } else {
                        call.reject("Export failed with unknown error")
                    }

                case .cancelled:
                    call.reject("Export was cancelled")

                default:
                    call.reject("Export failed with status: \(exportSession.status.rawValue)")
                }
            }
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

}
