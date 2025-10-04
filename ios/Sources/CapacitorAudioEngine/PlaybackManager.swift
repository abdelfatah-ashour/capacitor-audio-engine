import Foundation
@preconcurrency import AVFoundation
@preconcurrency import MediaPlayer

protocol PlaybackManagerDelegate: AnyObject {
    func playbackManager(_ manager: PlaybackManager, playbackStarted trackId: String)
    func playbackManager(_ manager: PlaybackManager, playbackPaused trackId: String)
    func playbackManager(_ manager: PlaybackManager, playbackStopped trackId: String)
    func playbackManager(_ manager: PlaybackManager, playbackError trackId: String, error: Error)
    func playbackManager(_ manager: PlaybackManager, playbackProgress trackId: String, currentPosition: Double, duration: Double)
}

class PlaybackManager: NSObject {
    weak var delegate: PlaybackManagerDelegate?

    // MARK: - Properties
    private var audioPlayers: [String: AVPlayer] = [:]
    private var currentTrackId: String?
    private var isEngineInitialized = false

    // MARK: - Track Management
    private var trackUrls: [String: String] = [:] // trackId -> URL mapping
    private var urlToTrackIdMap: [String: String] = [:] // URL -> trackId mapping
    private var trackCounter = 0

    // MARK: - Thread Safety
    private let playbackQueue = DispatchQueue(label: "playback-manager", qos: .userInteractive)
    private let playbackQueueKey = DispatchSpecificKey<String>()
    private let playbackQueueValue = "playback-manager-queue"

    // MARK: - Progress Tracking
    private var timeObservers: [String: Any] = [:]
    private let progressUpdateInterval: TimeInterval = 1.0

    // MARK: - Initialization

    override init() {
        super.init()
        playbackQueue.setSpecific(key: playbackQueueKey, value: playbackQueueValue)
        initializeAudioEngine()
    }

    deinit {
        cleanup()
    }

    private func initializeAudioEngine() {
        setupAudioSession()
        setupRemoteCommandCenter()
        setupNotifications()
        isEngineInitialized = true
        print("Audio engine initialized")
    }

    // MARK: - Track Management Helpers

    private func generateTrackId() -> String {
        let id = "track_\(trackCounter)"
        trackCounter += 1
        return id
    }

    private func resolveTrackId(_ identifier: String?) -> String? {
        guard let identifier = identifier else {
            return currentTrackId
        }

        // If it's already a trackId (starts with "track_"), return as is
        if identifier.hasPrefix("track_") {
            return audioPlayers.keys.contains(identifier) ? identifier : nil
        }

        // Otherwise, treat as URL and look up trackId
        return urlToTrackIdMap[identifier]
    }

    func getTrackId(for url: String) -> String? {
        return urlToTrackIdMap[url]
    }

    func getUrl(for trackId: String) -> String? {
        return trackUrls[trackId]
    }

    func getAllTrackIds() -> [String] {
        return Array(audioPlayers.keys)
    }

    func getAllUrls() -> [String] {
        return Array(urlToTrackIdMap.keys)
    }

    // MARK: - Core Audio Engine Methods

    func preloadTrack(trackId: String, url: String) -> Bool {
        guard isEngineInitialized else {
            print("[PlaybackManager] Audio engine not initialized")
            return false
        }

        print("[PlaybackManager] Starting preload for track \(trackId) with URL: \(url)")

        return performQueueOperation {
            do {
                let player = try createPlayer(for: url)
                print("[PlaybackManager] Created AVPlayer for track \(trackId)")

                audioPlayers[trackId] = player
                trackUrls[trackId] = url
                urlToTrackIdMap[url] = trackId
                setupPlayerObservers(for: trackId, player: player)

                print("[PlaybackManager] Track preloaded successfully: \(trackId) -> \(url)")
                return true
            } catch {
                print("[PlaybackManager] Failed to preload track \(trackId): \(error)")
                print("[PlaybackManager] Error details: \(error.localizedDescription)")
                return false
            }
        }
    }

    private func createPlayer(for url: String) throws -> AVPlayer {
        print("[PlaybackManager] Creating player for URL: \(url)")

        let playerUrl: URL

        // Handle different URL formats with enhanced error handling
        if url.hasPrefix("http://") || url.hasPrefix("https://") {
            // CDN/Remote URL handling
            print("[PlaybackManager] Processing remote URL")
            guard let remoteUrl = URL(string: url) else {
                print("[PlaybackManager] Failed to create URL from remote string: \(url)")
                throw PlaybackError.invalidTrackUrl("Invalid remote URL: \(url)")
            }
            playerUrl = remoteUrl
            print("[PlaybackManager] Remote URL created successfully: \(playerUrl)")

        } else if url.hasPrefix("file://") {
            // File URI handling with multiple fallback methods
            print("[PlaybackManager] Processing file URI")

            do {
                // Method 1: Try direct URL parsing
                guard let fileUrl = URL(string: url) else {
                    throw PlaybackError.invalidTrackUrl("Invalid file URI format: \(url)")
                }

                // Check if file exists
                if FileManager.default.fileExists(atPath: fileUrl.path) {
                    playerUrl = fileUrl
                    print("[PlaybackManager] File URI method 1 successful: \(playerUrl)")
                } else {
                    // Method 2: Try extracting path and creating file URL
                    let path = fileUrl.path
                    let fileUrl2 = URL(fileURLWithPath: path)
                    if FileManager.default.fileExists(atPath: fileUrl2.path) {
                        playerUrl = fileUrl2
                        print("[PlaybackManager] File URI method 2 successful: \(playerUrl)")
                    } else {
                        // Method 3: Try removing file:// prefix and creating file URL
                        let cleanPath = String(url.dropFirst(7)) // Remove "file://"
                        let fileUrl3 = URL(fileURLWithPath: cleanPath)
                        if FileManager.default.fileExists(atPath: fileUrl3.path) {
                            playerUrl = fileUrl3
                            print("[PlaybackManager] File URI method 3 successful: \(playerUrl)")
                        } else {
                            // Method 4: Try with Documents directory
                            let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.path ?? ""
                            let fullPath = documentsPath + "/" + cleanPath
                            let fileUrl4 = URL(fileURLWithPath: fullPath)
                            if FileManager.default.fileExists(atPath: fileUrl4.path) {
                                playerUrl = fileUrl4
                                print("[PlaybackManager] File URI method 4 successful: \(playerUrl)")
                            } else {
                                // Method 5: Try with Application Support directory
                                let appSupportPath = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.path ?? ""
                                let fullPath2 = appSupportPath + "/" + cleanPath
                                let fileUrl5 = URL(fileURLWithPath: fullPath2)
                                if FileManager.default.fileExists(atPath: fileUrl5.path) {
                                    playerUrl = fileUrl5
                                    print("[PlaybackManager] File URI method 5 successful: \(playerUrl)")
                                } else {
                                    print("[PlaybackManager] All file URI methods failed for: \(url)")
                                    print("[PlaybackManager] Tried paths:")
                                    print("  - \(fileUrl.path)")
                                    print("  - \(fileUrl2.path)")
                                    print("  - \(fileUrl3.path)")
                                    print("  - \(fullPath)")
                                    print("  - \(fullPath2)")
                                    throw PlaybackError.fileNotFound(path: url)
                                }
                            }
                        }
                    }
                }
            } catch {
                print("[PlaybackManager] File URI processing failed: \(error)")
                throw error
            }

        } else {
            // Direct file path handling with multiple fallback methods
            print("[PlaybackManager] Processing direct file path")

            do {
                // Method 1: Try as direct file path
                let fileUrl = URL(fileURLWithPath: url)
                if FileManager.default.fileExists(atPath: fileUrl.path) {
                    playerUrl = fileUrl
                    print("[PlaybackManager] Direct path method 1 successful: \(playerUrl)")
                } else {
                    // Method 2: Try with Documents directory
                    let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.path ?? ""
                    let fullPath = documentsPath + "/" + url
                    let fileUrl2 = URL(fileURLWithPath: fullPath)
                    if FileManager.default.fileExists(atPath: fileUrl2.path) {
                        playerUrl = fileUrl2
                        print("[PlaybackManager] Direct path method 2 successful: \(playerUrl)")
                    } else {
                        // Method 3: Try with Application Support directory
                        let appSupportPath = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?.path ?? ""
                        let fullPath2 = appSupportPath + "/" + url
                        let fileUrl3 = URL(fileURLWithPath: fullPath2)
                        if FileManager.default.fileExists(atPath: fileUrl3.path) {
                            playerUrl = fileUrl3
                            print("[PlaybackManager] Direct path method 3 successful: \(playerUrl)")
                        } else {
                            // Method 4: Try with Library directory
                            let libraryPath = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first?.path ?? ""
                            let fullPath3 = libraryPath + "/" + url
                            let fileUrl4 = URL(fileURLWithPath: fullPath3)
                            if FileManager.default.fileExists(atPath: fileUrl4.path) {
                                playerUrl = fileUrl4
                                print("[PlaybackManager] Direct path method 4 successful: \(playerUrl)")
                            } else {
                                print("[PlaybackManager] All direct path methods failed for: \(url)")
                                print("[PlaybackManager] Tried paths:")
                                print("  - \(fileUrl.path)")
                                print("  - \(fullPath)")
                                print("  - \(fullPath2)")
                                print("  - \(fullPath3)")
                                throw PlaybackError.fileNotFound(path: url)
                            }
                        }
                    }
                }
            } catch {
                print("[PlaybackManager] Direct path processing failed: \(error)")
                throw error
            }
        }

        // Create player item and player
        print("[PlaybackManager] Creating AVPlayerItem with URL: \(playerUrl)")
        let playerItem = AVPlayerItem(url: playerUrl)
        let player = AVPlayer(playerItem: playerItem)

        // Configure player for low latency
        player.automaticallyWaitsToMinimizeStalling = false

        print("[PlaybackManager] Player created successfully")
        return player
    }

    func preloadTracks(trackUrls: [String]) throws -> [[String: Any]] {
        guard !trackUrls.isEmpty else {
            throw PlaybackError.emptyPlaylist
        }

        print("[PlaybackManager] Starting preload of \(trackUrls.count) tracks")
        for (index, url) in trackUrls.enumerated() {
            print("[PlaybackManager] Track \(index): \(url)")
        }

        return performQueueOperation {
            var trackResults: [[String: Any]] = []

            for url in trackUrls {
                // Check if already preloaded
                if let existingTrackId = urlToTrackIdMap[url] {
                    print("[PlaybackManager] Track already preloaded: \(url) -> \(existingTrackId)")
                    var trackInfo: [String: Any] = ["url": url, "loaded": true]

                    // Get metadata
                    let metadata = extractTrackMetadata(url: url, trackId: existingTrackId)
                    trackInfo["mimeType"] = metadata.mimeType
                    trackInfo["duration"] = metadata.duration
                    trackInfo["size"] = metadata.size

                    trackResults.append(trackInfo)
                    continue
                }

                // Generate unique track ID
                let trackId = generateTrackId()
                print("[PlaybackManager] Preloading track \(trackId) with URL: \(url)")
                var trackInfo: [String: Any] = ["url": url]

                if preloadTrack(trackId: trackId, url: url) {
                    trackInfo["loaded"] = true
                    print("[PlaybackManager] Successfully preloaded track \(trackId)")

                    // Get metadata
                    let metadata = extractTrackMetadata(url: url, trackId: trackId)
                    trackInfo["mimeType"] = metadata.mimeType
                    trackInfo["duration"] = metadata.duration
                    trackInfo["size"] = metadata.size
                } else {
                    trackInfo["loaded"] = false
                    // Provide default values for failed tracks
                    trackInfo["mimeType"] = "audio/mpeg"
                    trackInfo["duration"] = 0.0
                    trackInfo["size"] = 0
                    print("[PlaybackManager] Failed to preload track \(trackId)")
                }

                trackResults.append(trackInfo)
            }

            print("[PlaybackManager] Successfully preloaded \(trackResults.filter { $0["loaded"] as? Bool == true }.count) tracks (total loaded: \(audioPlayers.count))")
            return trackResults
        }
    }

    private func extractTrackMetadata(url: String, trackId: String) -> (mimeType: String, duration: Double, size: Int64) {
        var mimeType = "audio/mpeg"
        var duration = 0.0
        var size: Int64 = 0

        // Determine MIME type from URL extension
        if let urlObject = URL(string: url) {
            let pathExtension = urlObject.pathExtension.lowercased()
            switch pathExtension {
            case "m4a":
                mimeType = "audio/m4a"
            case "mp3":
                mimeType = "audio/mpeg"
            case "wav":
                mimeType = "audio/wav"
            case "aac":
                mimeType = "audio/aac"
            default:
                mimeType = "audio/mpeg"
            }
        }

        // Get duration from player if available
        if let player = audioPlayers[trackId],
           let playerDuration = player.currentItem?.duration,
           !playerDuration.isIndefinite && playerDuration.seconds > 0 {
            duration = CMTimeGetSeconds(playerDuration)
        }

        // Get file size for local files
        if url.hasPrefix("file://") || (!url.hasPrefix("http://") && !url.hasPrefix("https://")) {
            let filePath = url.hasPrefix("file://") ? String(url.dropFirst(7)) : url
            let fileURL = URL(fileURLWithPath: filePath)

            if FileManager.default.fileExists(atPath: fileURL.path) {
                do {
                    let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
                    size = attributes[.size] as? Int64 ?? 0
                } catch {
                    print("[PlaybackManager] Failed to get file size for \(url): \(error)")
                }
            }
        }
        // For remote URLs, we can't get size without downloading

        return (mimeType, duration, size)
    }

    // MARK: - Playback Control Methods

    func play(identifier: String? = nil) {
        performQueueOperation {
            guard let trackId = resolveTrackId(identifier) else {
                let errorId = identifier ?? "current track"
                print("Track not found: \(errorId)")
                delegate?.playbackManager(self, playbackError: errorId, error: PlaybackError.playbackFailed("Track not found: \(errorId)"))
                return
            }

            guard let player = audioPlayers[trackId] else {
                print("Track not preloaded: \(trackId)")
                delegate?.playbackManager(self, playbackError: trackId, error: PlaybackError.playbackFailed("Track not preloaded: \(trackId)"))
                return
            }

            // Stop current track if playing a different one
            if let currentId = currentTrackId, currentId != trackId {
                stopTrack(currentId)
            }

            do {
                try setupAudioSessionForPlayback()
                player.play()
                currentTrackId = trackId
                startProgressTracking(for: trackId)

                delegate?.playbackManager(self, playbackStarted: trackId)
                print("Started playback: \(trackId)")
            } catch {
                print("Failed to start playback: \(trackId), error: \(error)")
                delegate?.playbackManager(self, playbackError: trackId, error: error)
            }
        }
    }

    // Legacy method for backward compatibility
    func play(trackId: String) {
        play(identifier: trackId)
    }

    func pause(identifier: String? = nil) {
        performQueueOperation {
            guard let trackId = resolveTrackId(identifier) else {
                print("Track not found: \(identifier ?? "current track")")
                return
            }

            guard let player = audioPlayers[trackId] else {
                print("Track not preloaded: \(trackId)")
                return
            }

            player.pause()
            delegate?.playbackManager(self, playbackPaused: trackId)
            print("Paused playback: \(trackId)")
        }
    }

    // Legacy method for backward compatibility
    func pause(trackId: String) {
        pause(identifier: trackId)
    }

    func stop(identifier: String? = nil) {
        performQueueOperation {
            guard let trackId = resolveTrackId(identifier) else {
                print("Track not found: \(identifier ?? "current track")")
                return
            }

            guard let player = audioPlayers[trackId] else {
                print("Track not preloaded: \(trackId)")
                return
            }

            player.pause()
            player.seek(to: .zero)

            if currentTrackId == trackId {
                currentTrackId = nil
                stopProgressTracking(for: trackId)
            }

            delegate?.playbackManager(self, playbackStopped: trackId)
            print("Stopped playback: \(trackId)")
        }
    }

    // Legacy method for backward compatibility
    func stop(trackId: String) {
        stop(identifier: trackId)
    }

    // Internal method for stopping without identifier resolution
    private func stopTrack(_ trackId: String) {
        performQueueOperation {
            guard let player = audioPlayers[trackId] else {
                print("Track not preloaded: \(trackId)")
                return
            }

            player.pause()
            player.seek(to: .zero)

            if currentTrackId == trackId {
                currentTrackId = nil
                stopProgressTracking(for: trackId)
            }

            delegate?.playbackManager(self, playbackStopped: trackId)
            print("Stopped playback: \(trackId)")
        }
    }

    func seek(identifier: String? = nil, to seconds: Double) {
        performQueueOperation {
            guard let trackId = resolveTrackId(identifier) else {
                print("Track not found: \(identifier ?? "current track")")
                return
            }

            guard let player = audioPlayers[trackId] else {
                print("Track not preloaded: \(trackId)")
                return
            }

            let time = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: time)
            print("Seeked to \(seconds)s in track: \(trackId)")
        }
    }

    // Legacy method for backward compatibility
    func seek(trackId: String, to seconds: Double) {
        seek(identifier: trackId, to: seconds)
    }

    // MARK: - Utility Methods

    func isPlaying(identifier: String? = nil) -> Bool {
        guard let trackId = resolveTrackId(identifier) else { return false }
        guard let player = audioPlayers[trackId] else { return false }
        return player.rate > 0
    }

    // Legacy method for backward compatibility
    func isPlaying(trackId: String) -> Bool {
        return isPlaying(identifier: trackId)
    }

    func getCurrentPosition(identifier: String? = nil) -> Double {
        guard let trackId = resolveTrackId(identifier) else { return 0.0 }
        guard let player = audioPlayers[trackId] else { return 0.0 }
        return player.currentTime().seconds
    }

    // Legacy method for backward compatibility
    func getCurrentPosition(trackId: String) -> Double {
        return getCurrentPosition(identifier: trackId)
    }

    func getDuration(identifier: String? = nil) -> Double {
        guard let trackId = resolveTrackId(identifier) else { return 0.0 }
        guard let player = audioPlayers[trackId],
              let duration = player.currentItem?.duration,
              !duration.isIndefinite else { return 0.0 }
        return duration.seconds
    }

    // Legacy method for backward compatibility
    func getDuration(trackId: String) -> Double {
        return getDuration(identifier: trackId)
    }

    func getCurrentTrackId() -> String? {
        return currentTrackId
    }

    func getCurrentTrackUrl() -> String? {
        guard let trackId = currentTrackId else { return nil }
        return trackUrls[trackId]
    }

    func unloadTrack(identifier: String) {
        performQueueOperation {
            guard let trackId = resolveTrackId(identifier) else {
                print("Track not found: \(identifier)")
                return
            }

            if let player = audioPlayers[trackId] {
                player.pause()
                stopProgressTracking(for: trackId)
            }
            audioPlayers.removeValue(forKey: trackId)
            trackUrls.removeValue(forKey: trackId)

            // Remove from URL mapping
            if let url = urlToTrackIdMap.first(where: { $0.value == trackId })?.key {
                urlToTrackIdMap.removeValue(forKey: url)
            }

            if currentTrackId == trackId {
                currentTrackId = nil
            }

            print("Unloaded track: \(trackId)")
        }
    }

    // Legacy method for backward compatibility
    func unloadTrack(trackId: String) {
        unloadTrack(identifier: trackId)
    }

    // MARK: - Progress Tracking

    private func startProgressTracking(for trackId: String) {
        guard let player = audioPlayers[trackId] else { return }

        stopProgressTracking(for: trackId)

        let interval = CMTime(seconds: progressUpdateInterval, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        let timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            guard let self = self,
                  let player = self.audioPlayers[trackId],
                  player.rate > 0 else { return }

            let currentPosition = player.currentTime().seconds
            let duration = self.getDuration(trackId: trackId)

            self.delegate?.playbackManager(self, playbackProgress: trackId, currentPosition: currentPosition, duration: duration)
        }

        timeObservers[trackId] = timeObserver
    }

    private func stopProgressTracking(for trackId: String) {
        if let timeObserver = timeObservers[trackId] {
            audioPlayers[trackId]?.removeTimeObserver(timeObserver)
            timeObservers.removeValue(forKey: trackId)
        }
    }

    // MARK: - Player Observers

    private func setupPlayerObservers(for trackId: String, player: AVPlayer) {
        // Observe player item status
        player.currentItem?.addObserver(self, forKeyPath: "status", options: [.new, .initial], context: nil)

        // Store trackId for use in observer
        if let playerItem = player.currentItem {
            objc_setAssociatedObject(playerItem, "trackId", trackId, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        }
    }

    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if keyPath == "status" {
            guard let playerItem = object as? AVPlayerItem,
                  let trackId = objc_getAssociatedObject(playerItem, "trackId") as? String else { return }

            switch playerItem.status {
            case .readyToPlay:
                print("Player item ready to play: \(trackId)")
            case .failed:
                if let error = playerItem.error {
                    print("Player item failed: \(trackId), error: \(error)")
                    delegate?.playbackManager(self, playbackError: trackId, error: error)
                }
            case .unknown:
                print("Player item status unknown: \(trackId)")
            @unknown default:
                print("Unknown player item status: \(trackId)")
            }
        }
    }

    // MARK: - Audio Session Management

    private func setupAudioSession() {
        let audioSession = AVAudioSession.sharedInstance()

        do {
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try audioSession.setActive(true)
            print("Audio session configured for playback")
        } catch {
            print("Failed to setup audio session: \(error)")
        }
    }

    private func setupAudioSessionForPlayback() throws {
        let audioSession = AVAudioSession.sharedInstance()

        do {
            try audioSession.setActive(true)
        } catch {
            print("Failed to activate audio session: \(error)")
            throw error
        }
    }

    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            guard let self = self, let trackId = self.currentTrackId else { return .commandFailed }
            self.play(trackId: trackId)
            return .success
        }

        commandCenter.pauseCommand.addTarget { [weak self] _ in
            guard let self = self, let trackId = self.currentTrackId else { return .commandFailed }
            self.pause(trackId: trackId)
            return .success
        }

        commandCenter.stopCommand.addTarget { [weak self] _ in
            guard let self = self, let trackId = self.currentTrackId else { return .commandFailed }
            self.stop(trackId: trackId)
            return .success
        }
    }

    private func setupNotifications() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(playerDidFinishPlaying),
            name: .AVPlayerItemDidPlayToEndTime,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioSessionInterrupted),
            name: AVAudioSession.interruptionNotification,
            object: nil
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioSessionRouteChanged),
            name: AVAudioSession.routeChangeNotification,
            object: nil
        )
    }

    // MARK: - Notification Handlers

    @objc private func playerDidFinishPlaying(notification: Notification) {
        guard let playerItem = notification.object as? AVPlayerItem,
              let trackId = objc_getAssociatedObject(playerItem, "trackId") as? String else { return }

        delegate?.playbackManager(self, playbackStopped: trackId)
        print("Track completed: \(trackId)")
    }

    @objc private func audioSessionInterrupted(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }

        switch type {
        case .began:
            if let trackId = currentTrackId {
                pause(trackId: trackId)
                print("Audio session interrupted - playback paused")
            }
        case .ended:
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)

            if options.contains(.shouldResume), let trackId = currentTrackId {
                do {
                    try setupAudioSessionForPlayback()
                    play(trackId: trackId)
                    print("Audio session interruption ended - playback resumed")
                } catch {
                    print("Failed to resume after interruption: \(error)")
                    delegate?.playbackManager(self, playbackError: trackId, error: error)
                }
            }
        @unknown default:
            break
        }
    }

    @objc private func audioSessionRouteChanged(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else { return }

        switch reason {
        case .oldDeviceUnavailable:
            if let trackId = currentTrackId {
                pause(trackId: trackId)
                print("Audio route changed - external device disconnected, playback paused")
            }
        case .newDeviceAvailable:
            print("Audio route changed - new device available")
        default:
            break
        }
    }

    // MARK: - Helper Methods

    private func performQueueOperation<T>(_ operation: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            return try operation()
        } else {
            return try playbackQueue.sync { try operation() }
        }
    }

    private func cleanup() {
        performQueueOperation {
            // Stop all playback
            if let trackId = currentTrackId {
                stopTrack(trackId)
            }

            // Remove all time observers
            for (trackId, _) in timeObservers {
                stopProgressTracking(for: trackId)
            }

            // Release all players
            for (_, player) in audioPlayers {
                player.pause()
            }
            audioPlayers.removeAll()
            trackUrls.removeAll()
            urlToTrackIdMap.removeAll()

            // Remove notification observers
            NotificationCenter.default.removeObserver(self)

            print("PlaybackManager cleaned up")
        }
    }
}
