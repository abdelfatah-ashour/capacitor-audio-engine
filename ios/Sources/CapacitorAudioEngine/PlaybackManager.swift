import Foundation
@preconcurrency import AVFoundation
@preconcurrency import MediaPlayer

protocol PlaybackManagerDelegate: AnyObject {
    func playbackManager(_ manager: PlaybackManager, trackDidChange track: AudioTrack, at index: Int)
    func playbackManager(_ manager: PlaybackManager, trackDidEnd track: AudioTrack, at index: Int)
    func playbackManager(_ manager: PlaybackManager, playbackDidStart track: AudioTrack, at index: Int)
    func playbackManager(_ manager: PlaybackManager, playbackDidPause track: AudioTrack, at index: Int)
    func playbackManager(_ manager: PlaybackManager, playbackDidFail error: Error)
    func playbackManager(_ manager: PlaybackManager, playbackProgress track: AudioTrack, at index: Int, currentPosition: Double, duration: Double, isPlaying: Bool)
    func playbackManager(_ manager: PlaybackManager, statusChanged track: AudioTrack?, at index: Int, status: PlaybackStatus, currentPosition: Double, duration: Double, isPlaying: Bool)
}

class PlaybackManager: NSObject {
    weak var delegate: PlaybackManagerDelegate?

    // MARK: - Properties
    private var player: AVPlayer?
    private var playerItems: [AVPlayerItem] = []
    private var playlist: [AudioTrack] = []
    private var currentIndex: Int = 0
    private var _status: PlaybackStatus = .idle
    private var preloadNext: Bool = true

    // Thread-safe status access
    private var status: PlaybackStatus {
        get {
            return performQueueOperation { _status }
        }
        set {
            performQueueOperation { _status = newValue }
        }
    }

    // MARK: - Caching
    private var artworkCache: [String: UIImage] = [:]

    // MARK: - Observers
    private var timeObserver: Any?
    private var playerItemObservers: [NSKeyValueObservation] = []

    // MARK: - Queue for thread safety
    private let playbackQueue = DispatchQueue(label: "playback-manager", qos: .userInteractive)
    private let playbackQueueKey = DispatchSpecificKey<String>()
    private let playbackQueueValue = "playback-manager-queue"

    // MARK: - Event debouncing
    private var lastStatusChangeTime: TimeInterval = 0
    private let statusChangeDebounceInterval: TimeInterval = 0.1 // 100ms debounce

    // MARK: - Helper Methods

    /// Thread-safe queue operation wrapper
    private func performQueueOperation<T>(_ operation: () throws -> T) rethrows -> T {
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            return try operation()
        } else {
            return try playbackQueue.sync { try operation() }
        }
    }

    /// Execute operation with error handling
    private func executeWithErrorHandling<T>(_ operation: () throws -> T) -> Result<T, Error> {
        do {
            let result = try operation()
            return .success(result)
        } catch {
            return .failure(error)
        }
    }

    override init() {
        super.init()
        playbackQueue.setSpecific(key: playbackQueueKey, value: playbackQueueValue)
        setupAudioSession()
        setupRemoteCommandCenter()
        setupNotifications()
    }

    deinit {
        cleanup()
    }

    // MARK: - Public Methods

    func preloadTracks(trackUrls: [String], preloadNext: Bool = true) throws -> [[String: Any]] {
        guard !trackUrls.isEmpty else {
            throw PlaybackError.emptyPlaylist
        }

        // Convert URLs to AudioTrack objects
        let tracks = trackUrls.enumerated().map { index, url in
            AudioTrack(id: "track_\(index)", url: url, title: nil, artist: nil, artworkUrl: nil)
        }

        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            return try preloadTracksInternal(tracks, preloadNext: preloadNext)
        } else {
            var thrownError: Error?
            var result: [[String: Any]] = []

            playbackQueue.sync {
                do {
                    result = try self.preloadTracksInternal(tracks, preloadNext: preloadNext)
                } catch {
                    thrownError = error
                }
            }

            if let error = thrownError {
                throw error
            }

            return result
        }
    }

    private func preloadTracksInternal(_ tracks: [AudioTrack], preloadNext: Bool) throws -> [[String: Any]] {
        cleanup()

        self.playlist = tracks
        self.preloadNext = preloadNext
        self.currentIndex = 0
        self.status = .loading

        // Track results for each URL
        var trackResults: [[String: Any]] = []
        var validPlayerItems: [AVPlayerItem] = []

        for track in tracks {
            var trackInfo: [String: Any] = ["url": track.url]

            let url: URL?

            // Handle different URL formats (remote URLs, file URIs, and local paths)
            if track.url.hasPrefix("http://") || track.url.hasPrefix("https://") {
                // Remote URL
                if let remoteUrl = URL(string: track.url) {
                    url = remoteUrl
                } else {
                    print("Invalid remote URL for track: \(track.title ?? track.id)")
                    url = nil
                }
            } else if track.url.hasPrefix("file://") {
                // File URI
                if let fileUrl = URL(string: track.url) {
                    url = fileUrl
                } else {
                    print("Invalid file URI for track: \(track.title ?? track.id)")
                    url = nil
                }
            } else {
                // Assume it's a local file path
                let fileUrl = URL(fileURLWithPath: track.url)

                // Verify that the file exists for local paths
                if FileManager.default.fileExists(atPath: fileUrl.path) {
                    url = fileUrl
                } else {
                    print("File does not exist for track: \(track.title ?? track.id) at path: \(track.url)")
                    url = nil
                }
            }

            if let validUrl = url {
                // Create player item and extract metadata
                let playerItem = AVPlayerItem(url: validUrl)
                let asset = playerItem.asset

                // For preloading, we'll do minimal synchronous checks and defer heavy loading
                // This avoids priority inversion while still providing useful information

                // Get basic file information synchronously (fast operations)
                // Get MIME type from file extension or asset
                if let mimeType = getMimeType(for: validUrl) {
                    trackInfo["mimeType"] = mimeType
                }

                // Get file size for local files (fast for local files)
                if validUrl.isFileURL {
                    do {
                        let attributes = try FileManager.default.attributesOfItem(atPath: validUrl.path)
                        if let fileSize = attributes[.size] as? NSNumber {
                            trackInfo["size"] = fileSize.intValue
                        }
                    } catch {
                        print("Failed to get file size for: \(track.url)")
                    }
                }

                // For duration, we'll try synchronous access first (works for many formats)
                let duration = CMTimeGetSeconds(asset.duration)
                if !duration.isNaN && duration.isFinite && duration > 0 {
                    trackInfo["duration"] = duration
                } else {
                    // Duration not immediately available - will be loaded asynchronously later
                    // Mark as unknown for now
                    trackInfo["duration"] = 0.0
                }

                validPlayerItems.append(playerItem)
                trackInfo["loaded"] = true
            } else {
                trackInfo["loaded"] = false
            }

            trackResults.append(trackInfo)
        }

        self.playerItems = validPlayerItems

        // Initialize player with first valid item if any exist
        if let firstItem = self.playerItems.first {
            self.player = AVPlayer(playerItem: firstItem)
            self.setupPlayerObservers()
            self.status = .idle

            // Preload next track if enabled
            if self.preloadNext && self.playerItems.count > 1 {
                self.preloadTrack(at: 1)
            }
        } else {
            self.status = .idle
        }

        // Load complete metadata asynchronously in the background
        // This avoids blocking the initial preload response
        self.loadCompleteMetadata()

        return trackResults
    }

    private func getMimeType(for url: URL) -> String? {
        let pathExtension = url.pathExtension.lowercased()

        switch pathExtension {
        case "mp3":
            return "audio/mpeg"
        case "m4a":
            return "audio/mp4"
        case "aac":
            return "audio/aac"
        case "wav":
            return "audio/wav"
        case "flac":
            return "audio/flac"
        case "ogg":
            return "audio/ogg"
        default:
            return "audio/mp4" // Default for iOS
        }
    }

    /// Asynchronously load complete metadata for tracks that need it
    /// This is called after the initial fast preload to fill in missing information
    private func loadCompleteMetadata() {
        // Load metadata for tracks asynchronously in the background
        DispatchQueue.global(qos: .utility).async { [weak self] in
            guard let self = self else { return }

            for (index, playerItem) in self.playerItems.enumerated() {
                let asset = playerItem.asset

                // Only load if duration is not already available
                let currentDuration = CMTimeGetSeconds(asset.duration)
                if currentDuration.isNaN || currentDuration <= 0 {
                    // Load duration asynchronously
                    asset.loadValuesAsynchronously(forKeys: ["duration"]) {
                        let duration = CMTimeGetSeconds(asset.duration)
                        if !duration.isNaN && duration.isFinite && duration > 0 {
                            print("Loaded duration for track \(index): \(duration) seconds")
                        }
                    }
                }
            }
        }
    }

    func play() throws {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            try playInternal()
        } else {
            var thrownError: Error?

            playbackQueue.sync {
                do {
                    try self.playInternal()
                } catch {
                    thrownError = error
                }
            }

            if let error = thrownError {
                throw error
            }
        }
    }

    private func playInternal() throws {
        print("PlaybackManager: Starting playInternal()")

        guard let player = self.player else {
            print("PlaybackManager: No player initialized")
            throw PlaybackError.playbackFailed("No player initialized")
        }
        print("PlaybackManager: Player available")

        guard let currentItem = player.currentItem else {
            print("PlaybackManager: No current item to play")
            throw PlaybackError.playbackFailed("No current item to play")
        }
        print("PlaybackManager: Current item available")
        print("PlaybackManager: Current item URL: \(String(describing: (currentItem.asset as? AVURLAsset)?.url))")

        // Check if the current item is ready to play
        print("PlaybackManager: Current item status: \(currentItem.status.rawValue)")
        if currentItem.status == .failed {
            print("PlaybackManager: Current item failed")
            if let error = currentItem.error as NSError? {
                print("PlaybackManager: Player item error details:")
                print("  Domain: \(error.domain)")
                print("  Code: \(error.code)")
                print("  Description: \(error.localizedDescription)")
                print("  UserInfo: \(error.userInfo)")
                throw PlaybackError.playbackFailed("Player item failed: \(error.localizedDescription)")
            } else {
                throw PlaybackError.playbackFailed("Player item failed with unknown error")
            }
        }

        guard self.status == .idle || self.status == .paused || self.status == .stopped else {
            print("PlaybackManager: Already in playing state, returning")
            return // Already playing
        }
        print("PlaybackManager: Status check passed, current status: \(self.status)")

        // Setup audio session for playback
        print("PlaybackManager: Setting up audio session for playback")
        do {
            try self.setupAudioSessionForPlayback()
            print("PlaybackManager: Audio session setup completed successfully")
        } catch {
            print("PlaybackManager: Audio session setup failed: \(error)")
            throw error
        }

        print("PlaybackManager: Calling player.play()")
        player.play()
        print("PlaybackManager: player.play() completed")

        self.status = .playing
        print("PlaybackManager: Status set to playing")

        if let currentTrack = self.getCurrentTrack() {
            print("PlaybackManager: Notifying delegate of playback start")
            self.delegate?.playbackManager(self, playbackDidStart: currentTrack, at: self.currentIndex)
            self.updateNowPlayingInfo()
        }

        self.emitStatusChange()
        print("PlaybackManager: playInternal() completed successfully")
    }

    func pause() {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            pauseInternal()
        } else {
            playbackQueue.sync {
                self.pauseInternal()
            }
        }
    }

    private func pauseInternal() {
        guard let player = self.player else { return }

        player.pause()
        self.status = .paused

        if let currentTrack = self.getCurrentTrack() {
            self.delegate?.playbackManager(self, playbackDidPause: currentTrack, at: self.currentIndex)
        }

        self.emitStatusChange()
    }

    func resume() {
        do {
            try play()
        } catch {
            print("Failed to resume playback: \(error)")
            delegate?.playbackManager(self, playbackDidFail: error)
        }
    }

    func stop() {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            stopInternal()
        } else {
            playbackQueue.sync {
                self.stopInternal()
            }
        }
    }

    private func stopInternal() {
        guard let player = self.player else { return }

        player.pause()
        player.seek(to: .zero)
        self.status = .stopped

        self.emitStatusChange()
    }

    func seek(to seconds: Double) {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            seekInternal(to: seconds)
        } else {
            playbackQueue.sync {
                self.seekInternal(to: seconds)
            }
        }
    }

    private func seekInternal(to seconds: Double) {
        guard let player = self.player else { return }

        let time = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        player.seek(to: time)
    }

    func skipToNext() {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            skipToNextInternal()
        } else {
            playbackQueue.sync {
                self.skipToNextInternal()
            }
        }
    }

    private func skipToNextInternal() {
        guard self.currentIndex < self.playlist.count - 1 else { return }

        self.currentIndex += 1
        self.switchToCurrentTrack()
    }

    func skipToPrevious() {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            skipToPreviousInternal()
        } else {
            playbackQueue.sync {
                self.skipToPreviousInternal()
            }
        }
    }

    private func skipToPreviousInternal() {
        guard self.currentIndex > 0 else { return }

        self.currentIndex -= 1
        self.switchToCurrentTrack()
    }

    func skipToIndex(_ index: Int) {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            skipToIndexInternal(index)
        } else {
            playbackQueue.sync {
                self.skipToIndexInternal(index)
            }
        }
    }

    private func skipToIndexInternal(_ index: Int) {
        guard index >= 0 && index < self.playlist.count else { return }

        self.currentIndex = index
        self.switchToCurrentTrack()
    }

    func getCurrentTrack() -> AudioTrack? {
        guard currentIndex >= 0 && currentIndex < playlist.count else { return nil }
        return playlist[currentIndex]
    }

    func getCurrentIndex() -> Int {
        return currentIndex
    }

    func getCurrentPosition() -> Double {
        guard let player = player else { return 0.0 }
        return player.currentTime().seconds
    }

    func getDuration() -> Double {
        guard let player = player,
              let duration = player.currentItem?.duration,
              !duration.isIndefinite else { return 0.0 }
        return duration.seconds
    }

    func isPlaying() -> Bool {
        return status == .playing
    }

    func getStatus() -> PlaybackStatus {
        return performQueueOperation { _status }
    }

    // MARK: - Per-URL Playback Methods

    func playByUrl(_ url: String) throws {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            try playByUrlInternal(url)
        } else {
            var thrownError: Error?

            playbackQueue.sync {
                do {
                    try self.playByUrlInternal(url)
                } catch {
                    thrownError = error
                }
            }

            if let error = thrownError {
                throw error
            }
        }
    }

    private func playByUrlInternal(_ url: String) throws {
        guard let trackIndex = findTrackIndex(by: url) else {
            throw PlaybackError.playbackFailed("Track with URL '\(url)' not found in preloaded tracks")
        }

        // Switch to the track and play
        self.currentIndex = trackIndex
        self.switchToCurrentTrack()
        try self.playInternal()
    }

    func pauseByUrl(_ url: String) {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            pauseByUrlInternal(url)
        } else {
            playbackQueue.sync {
                self.pauseByUrlInternal(url)
            }
        }
    }

    private func pauseByUrlInternal(_ url: String) {
        guard let trackIndex = findTrackIndex(by: url) else {
            print("Track with URL '\(url)' not found in preloaded tracks")
            return
        }

        // Only pause if this is the currently playing track
        if trackIndex == self.currentIndex {
            self.pauseInternal()
        }
    }

    func resumeByUrl(_ url: String) {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            resumeByUrlInternal(url)
        } else {
            playbackQueue.sync {
                self.resumeByUrlInternal(url)
            }
        }
    }

    private func resumeByUrlInternal(_ url: String) {
        let result = executeWithErrorHandling {
            guard let trackIndex = findTrackIndex(by: url) else {
                throw PlaybackError.invalidTrackUrl("Track with URL '\(url)' not found in preloaded tracks")
            }

            // Switch to the track and resume/play
            self.currentIndex = trackIndex
            self.switchToCurrentTrack()
            try self.playInternal()
        }

        switch result {
        case .success:
            print("Successfully resumed playback for URL: \(url)")
        case .failure(let error):
            print("Failed to resume playback for URL '\(url)': \(error)")
            self.delegate?.playbackManager(self, playbackDidFail: error)
        }
    }

    func stopByUrl(_ url: String) {
        // Check if we're already on the playback queue to avoid deadlock
        if DispatchQueue.getSpecific(key: playbackQueueKey) == playbackQueueValue {
            stopByUrlInternal(url)
        } else {
            playbackQueue.sync {
                self.stopByUrlInternal(url)
            }
        }
    }

    private func stopByUrlInternal(_ url: String) {
        guard let trackIndex = findTrackIndex(by: url) else {
            print("Track with URL '\(url)' not found in preloaded tracks")
            return
        }

        // Only stop if this is the currently playing track
        if trackIndex == self.currentIndex {
            self.stopInternal()
        }
    }

    func seekByUrl(_ url: String, to seconds: Double) {
        playbackQueue.sync {
            guard let trackIndex = findTrackIndex(by: url) else {
                print("Track with URL '\(url)' not found in preloaded tracks")
                return
            }

            // Switch to the track and seek
            self.currentIndex = trackIndex
            self.switchToCurrentTrack()

            guard let player = self.player else { return }
            let time = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: time)
        }
    }

    // MARK: - Helper Methods

    /// Find track index with URL normalization to handle encoding inconsistencies and different URL formats
    private func findTrackIndex(by url: String) -> Int? {
        let normalizedUrl = normalizeUrl(url)
        return playlist.firstIndex { track in
            let normalizedTrackUrl = normalizeUrl(track.url)
            // Check direct match first
            if normalizedTrackUrl == normalizedUrl {
                return true
            }

            // For local files, also check if the path components match
            // This handles cases where one is a file:// URI and the other is a path
            if !url.hasPrefix("http") && !track.url.hasPrefix("http") {
                let urlPath = extractFilePath(from: normalizedUrl)
                let trackPath = extractFilePath(from: normalizedTrackUrl)
                return urlPath == trackPath
            }

            return false
        }
    }

    /// Extract file path from URL string (handles both file:// URIs and direct paths)
    private func extractFilePath(from urlString: String) -> String {
        if urlString.hasPrefix("file://") {
            return URL(string: urlString)?.path ?? urlString
        } else {
            return urlString
        }
    }

    /// Normalize URL to handle encoding inconsistencies
    private func normalizeUrl(_ url: String) -> String {
        guard let decodedUrl = url.removingPercentEncoding else { return url }
        return decodedUrl.lowercased().trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Private Methods

        private func setupAudioSession() {
        // Don't configure audio session here - let the main plugin handle it
        print("PlaybackManager: Using shared audio session configuration")
    }

    private func setupAudioSessionForPlayback() throws {
        let audioSession = AVAudioSession.sharedInstance()

        // Don't change the category - use the existing .playAndRecord category from the main plugin
        // This prevents conflicts between recording and playback sessions
        // Just ensure the session is active for playback
        do {
            // Check if session is already active and compatible
            if !audioSession.isOtherAudioPlaying {
                try audioSession.setActive(true)
                print("PlaybackManager: Audio session activated for playback")
            } else {
                print("PlaybackManager: Other audio is playing, trying to activate with mix option")
                // Try to activate with mixing if other audio is playing
                try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
            }
        } catch let error as NSError {
            // Provide detailed error information for debugging
            print("PlaybackManager: Failed to activate audio session for playback")
            print("Error domain: \(error.domain), code: \(error.code)")
            print("Error description: \(error.localizedDescription)")
            if let underlyingError = error.userInfo[NSUnderlyingErrorKey] as? NSError {
                print("Underlying error: \(underlyingError.localizedDescription)")
            }
            throw error
        }
    }

    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            do {
                try self?.play()
                return .success
            } catch {
                print("Remote play command failed: \(error)")
                return .commandFailed
            }
        }

        commandCenter.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }

        commandCenter.nextTrackCommand.addTarget { [weak self] _ in
            self?.skipToNext()
            return .success
        }

        commandCenter.previousTrackCommand.addTarget { [weak self] _ in
            self?.skipToPrevious()
            return .success
        }

        commandCenter.changePlaybackPositionCommand.addTarget { [weak self] event in
            if let event = event as? MPChangePlaybackPositionCommandEvent {
                self?.seek(to: event.positionTime)
                return .success
            }
            return .commandFailed
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
            selector: #selector(playerDidFailToPlay),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: nil
        )

        // Handle audio session interruptions (phone calls, AirPods disconnect, etc.)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(audioSessionInterupted),
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

    private func setupPlayerObservers() {
        guard let player = player else { return }

        // Clean up existing observers first
        cleanupObservers()

        // Add time observer for progress updates - reduced frequency for better performance
        let interval = CMTime(seconds: 1.0, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: playbackQueue) { [weak self] _ in
            guard let self = self else { return }

            // Only emit progress if we're actually playing to reduce unnecessary events
            guard self.status == .playing else { return }

            // Emit progress updates on main queue to ensure thread safety
            DispatchQueue.main.async {
                if let currentTrack = self.getCurrentTrack() {
                    let currentPosition = self.getCurrentPosition()
                    let duration = self.getDuration()
                    let isPlaying = self.isPlaying()

                    self.delegate?.playbackManager(
                        self,
                        playbackProgress: currentTrack,
                        at: self.currentIndex,
                        currentPosition: currentPosition,
                        duration: duration,
                        isPlaying: isPlaying
                    )
                }
            }
        }

        // Add KVO observers for player item status
        setupPlayerItemObservers()
    }

    private func setupPlayerItemObservers() {
        guard let currentItem = player?.currentItem else { return }

        // Observe player item status for readiness detection
        let statusObserver = currentItem.observe(\.status, options: [.new, .initial]) { [weak self] item, _ in
            DispatchQueue.main.async {
                self?.handlePlayerItemStatusChange(item)
            }
        }
        playerItemObservers.append(statusObserver)

        // Observe duration for better progress reporting
        let durationObserver = currentItem.observe(\.duration, options: [.new]) { [weak self] _, _ in
            DispatchQueue.main.async {
                self?.updateNowPlayingInfo()
            }
        }
        playerItemObservers.append(durationObserver)
    }

    private func handlePlayerItemStatusChange(_ item: AVPlayerItem) {
        switch item.status {
        case .readyToPlay:
            print("Player item ready to play")
        case .failed:
            if let error = item.error {
                let playbackError = PlaybackError.playbackFailed("Player item failed: \(error.localizedDescription)")
                delegate?.playbackManager(self, playbackDidFail: playbackError)
            }
        case .unknown:
            print("Player item status unknown")
        @unknown default:
            print("Unknown player item status")
        }
    }

    /// Clean up observers to prevent memory leaks and duplicate observers
    private func cleanupObservers() {
        // Remove time observer
        if let timeObserver = timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }

        // Remove KVO observers
        playerItemObservers.forEach { $0.invalidate() }
        playerItemObservers.removeAll()
    }

    private func switchToCurrentTrack() {
        guard currentIndex >= 0 && currentIndex < playerItems.count else { return }

        let wasPlaying = (status == .playing)
        let newItem = playerItems[currentIndex]

        // Reset status to allow proper playback control after track switch
        if status == .playing {
            status = .paused
        }

        // Clean up observers before switching
        cleanupObservers()

        player?.replaceCurrentItem(with: newItem)

        // Restore observers after switching
        setupPlayerObservers()

        if let currentTrack = getCurrentTrack() {
            delegate?.playbackManager(self, trackDidChange: currentTrack, at: currentIndex)

            if wasPlaying {
                // Wait for item to be ready before playing
                waitForPlayerItemReady(newItem) { [weak self] success in
                    if success {
                        do {
                            try self?.playInternal()
                        } catch {
                            print("Failed to play after track switch: \(error)")
                            self?.delegate?.playbackManager(self!, playbackDidFail: error)
                        }
                    }
                }
            }

            updateNowPlayingInfo()
        }

        // Preload next track if enabled
        if preloadNext && currentIndex + 1 < playerItems.count {
            preloadTrack(at: currentIndex + 1)
        }
    }

    /// Wait for player item to be ready before attempting playback
    private func waitForPlayerItemReady(_ item: AVPlayerItem, completion: @escaping (Bool) -> Void) {
        if item.status == .readyToPlay {
            completion(true)
            return
        }

        // Set a timeout for readiness check
        let timeoutTime = DispatchTime.now() + .seconds(5)
        let observer = item.observe(\.status) { item, _ in
            if item.status == .readyToPlay {
                completion(true)
            } else if item.status == .failed {
                completion(false)
            }
        }

        DispatchQueue.main.asyncAfter(deadline: timeoutTime) {
            observer.invalidate()
            if item.status != .readyToPlay {
                completion(false)
            }
        }
    }

    private func preloadTrack(at index: Int) {
        guard index >= 0 && index < playerItems.count else { return }

        let playerItem = playerItems[index]

        // Check if item is already sufficiently buffered
        if playerItem.status == .readyToPlay {
            print("Track at index \(index) already preloaded")
            return
        }

        // Force buffering by accessing duration and other properties
        _ = playerItem.duration
        _ = playerItem.asset.duration

        // Optional: trigger buffering by seeking to start
        if playerItem.status == .unknown {
            print("Preloading track at index \(index)")
            // The item will start loading automatically
        }
    }

    private func updateNowPlayingInfo() {
        guard let track = getCurrentTrack() else { return }

        var nowPlayingInfo: [String: Any] = [:]

        nowPlayingInfo[MPMediaItemPropertyTitle] = track.title ?? "Unknown Title"
        nowPlayingInfo[MPMediaItemPropertyArtist] = track.artist ?? "Unknown Artist"
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = getCurrentPosition()
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = getDuration()
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying() ? 1.0 : 0.0

        // Check cache first for artwork to avoid redundant network loads
        if let artworkUrl = track.artworkUrl {
            if let cachedImage = artworkCache[track.id] {
                // Use cached artwork
                nowPlayingInfo[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: cachedImage.size) { _ in cachedImage }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            } else if let url = URL(string: artworkUrl) {
                // Load and cache artwork
                loadArtwork(from: url, trackId: track.id) { image in
                    if let image = image {
                        nowPlayingInfo[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                    }
                    MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
                }
            } else {
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            }
        } else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }
    }

    private func emitStatusChange() {
        let now = CACurrentMediaTime()

        // Debounce rapid status changes to prevent excessive delegate calls
        guard now - lastStatusChangeTime >= statusChangeDebounceInterval else {
            return
        }

        lastStatusChangeTime = now

        // Ensure delegate calls are made on main thread for UI consistency
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            let currentTrack = self.getCurrentTrack()
            let currentPosition = self.getCurrentPosition()
            let duration = self.getDuration()
            let isPlaying = self.isPlaying()

            self.delegate?.playbackManager(
                self,
                statusChanged: currentTrack,
                at: self.currentIndex,
                status: self.status,
                currentPosition: currentPosition,
                duration: duration,
                isPlaying: isPlaying
            )
        }
    }

    private func loadArtwork(from url: URL, trackId: String, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url) { [weak self] data, _, error in
            DispatchQueue.main.async {
                if let data = data, let image = UIImage(data: data) {
                    // Cache the artwork for future use
                    self?.artworkCache[trackId] = image
                    completion(image)
                } else {
                    if let error = error {
                        print("Failed to load artwork: \(error.localizedDescription)")
                    }
                    completion(nil)
                }
            }
        }.resume()
    }

    private func cleanup() {
        // Clean up observers
        cleanupObservers()

        // Remove notification observers
        NotificationCenter.default.removeObserver(self)

        // Stop player
        player?.pause()
        player = nil
        playerItems.removeAll()
        playlist.removeAll()

        // Clear artwork cache
        artworkCache.removeAll()

        // Reset status
        performQueueOperation {
            _status = .idle
        }
    }

    // MARK: - Notification Handlers

    @objc private func playerDidFinishPlaying(notification: Notification) {
        guard let playerItem = notification.object as? AVPlayerItem,
              playerItem == player?.currentItem else { return }

        if let currentTrack = getCurrentTrack() {
            delegate?.playbackManager(self, trackDidEnd: currentTrack, at: currentIndex)
        }

        // Auto-advance to next track
        if currentIndex < playlist.count - 1 {
            skipToNext()
        } else {
            status = .stopped
        }
    }

    @objc private func playerDidFailToPlay(notification: Notification) {
        let error = PlaybackError.playbackFailed("Failed to play audio item")
        delegate?.playbackManager(self, playbackDidFail: error)
    }

    @objc private func audioSessionInterupted(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            // Interruption began - pause playback and preserve state
            if status == .playing {
                pause()
                print("Audio session interrupted - playback paused")
            }
        case .ended:
            // Interruption ended - optionally resume playback
            guard let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt else { return }
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)

            if options.contains(.shouldResume) {
                // Reactivate audio session
                do {
                    try setupAudioSessionForPlayback()
                    // Resume playback if it was playing before interruption
                    if status == .paused {
                        try play()
                        print("Audio session interruption ended - playback resumed")
                    }
                } catch {
                    print("Failed to resume after interruption: \(error)")
                    delegate?.playbackManager(self, playbackDidFail: error)
                }
            }
        @unknown default:
            break
        }
    }

    @objc private func audioSessionRouteChanged(notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        switch reason {
        case .oldDeviceUnavailable:
            // Headphones or external device was disconnected
            if status == .playing {
                pause()
                print("Audio route changed - external device disconnected, playback paused")
            }
        case .newDeviceAvailable:
            // New audio device connected
            print("Audio route changed - new device available")
        default:
            break
        }
    }
}

// MARK: - Supporting Types

enum PlaybackStatus {
    case idle
    case loading
    case playing
    case paused
    case stopped
}

struct AudioTrack: Equatable, Codable {
    let id: String
    let url: String
    let title: String?
    let artist: String?
    let artworkUrl: String?

    static func == (lhs: AudioTrack, rhs: AudioTrack) -> Bool {
        return lhs.id == rhs.id && lhs.url == rhs.url
    }
}

