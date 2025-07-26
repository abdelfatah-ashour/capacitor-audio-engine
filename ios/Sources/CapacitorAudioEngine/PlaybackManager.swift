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
    private var status: PlaybackStatus = .idle
    private var preloadNext: Bool = true

    // MARK: - Observers
    private var timeObserver: Any?
    private var playerItemObservers: [NSKeyValueObservation] = []

    // MARK: - Queue for thread safety
    private let playbackQueue = DispatchQueue(label: "playback-manager", qos: .userInteractive)
    private let playbackQueueKey = DispatchSpecificKey<String>()
    private let playbackQueueValue = "playback-manager-queue"

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

    func initPlaylist(tracks: [AudioTrack], preloadNext: Bool = true) throws {
        guard !tracks.isEmpty else {
            throw PlaybackError.emptyPlaylist
        }

        var thrownError: Error?

        playbackQueue.sync {
            do {
                cleanup()

                self.playlist = tracks
                self.preloadNext = preloadNext
                self.currentIndex = 0
                self.status = .loading

                // Validate URLs and create player items
                var validPlayerItems: [AVPlayerItem] = []
                for track in tracks {
                    guard let url = URL(string: track.url) else {
                        print("Invalid URL for track: \(track.title ?? track.id)")
                        continue
                    }

                    // Create player item and check if it's valid
                    let playerItem = AVPlayerItem(url: url)
                    validPlayerItems.append(playerItem)
                }

                guard !validPlayerItems.isEmpty else {
                    throw PlaybackError.invalidTrackUrl("No valid tracks found")
                }

                self.playerItems = validPlayerItems

                // Initialize player with first item
                if let firstItem = self.playerItems.first {
                    self.player = AVPlayer(playerItem: firstItem)
                    self.setupPlayerObservers()
                    self.status = .idle

                    // Preload next track if enabled
                    if self.preloadNext && self.playerItems.count > 1 {
                        self.preloadTrack(at: 1)
                    }
                }
            } catch {
                thrownError = error
            }
        }

        if let error = thrownError {
            throw error
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
        guard let player = self.player else {
            throw PlaybackError.playbackFailed("No player initialized")
        }

        guard let currentItem = player.currentItem else {
            throw PlaybackError.playbackFailed("No current item to play")
        }

        // Check if the current item is ready to play
        if currentItem.status == .failed {
            if let error = currentItem.error {
                throw PlaybackError.playbackFailed("Player item failed: \(error.localizedDescription)")
            } else {
                throw PlaybackError.playbackFailed("Player item failed with unknown error")
            }
        }

        guard self.status == .idle || self.status == .paused || self.status == .stopped else {
            return // Already playing
        }

        // Setup audio session for playback
        try self.setupAudioSessionForPlayback()

        player.play()
        self.status = .playing

        if let currentTrack = self.getCurrentTrack() {
            self.delegate?.playbackManager(self, playbackDidStart: currentTrack, at: self.currentIndex)
            self.updateNowPlayingInfo()
        }

        self.emitStatusChange()
    }

    func pause() {
        playbackQueue.sync {
            guard let player = self.player else { return }

            player.pause()
            self.status = .paused

            if let currentTrack = self.getCurrentTrack() {
                self.delegate?.playbackManager(self, playbackDidPause: currentTrack, at: self.currentIndex)
            }

            self.emitStatusChange()
        }
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
        playbackQueue.sync {
            guard let player = self.player else { return }

            player.pause()
            player.seek(to: .zero)
            self.status = .stopped

            self.emitStatusChange()
        }
    }

    func seek(to seconds: Double) {
        playbackQueue.sync {
            guard let player = self.player else { return }

            let time = CMTime(seconds: seconds, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
            player.seek(to: time)
        }
    }

    func skipToNext() {
        playbackQueue.sync {
            guard self.currentIndex < self.playlist.count - 1 else { return }

            self.currentIndex += 1
            self.switchToCurrentTrack()
        }
    }

    func skipToPrevious() {
        playbackQueue.sync {
            guard self.currentIndex > 0 else { return }

            self.currentIndex -= 1
            self.switchToCurrentTrack()
        }
    }

    func skipToIndex(_ index: Int) {
        playbackQueue.sync {
            guard index >= 0 && index < self.playlist.count else { return }

            self.currentIndex = index
            self.switchToCurrentTrack()
        }
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
        return status
    }

    // MARK: - Private Methods

        private func setupAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])
            try audioSession.setActive(false) // Don't activate yet
        } catch {
            print("Failed to setup audio session: \(error)")
        }
    }

    private func setupAudioSessionForPlayback() throws {
        let audioSession = AVAudioSession.sharedInstance()

        // Always ensure proper configuration for optimal playback volume
        // Remove .mixWithOthers to prevent volume reduction
        try audioSession.setCategory(.playAndRecord,
                                    mode: .default,
                                    options: [.allowAirPlay, .allowBluetooth, .allowBluetoothA2DP, .defaultToSpeaker])

        // Always activate the session for playback - don't check isOtherAudioPlaying
        // as it can prevent proper activation after recording
        try audioSession.setActive(true)

        print("Audio session configured and activated for playback")
    }

    private func setupRemoteCommandCenter() {
        let commandCenter = MPRemoteCommandCenter.shared()

        commandCenter.playCommand.addTarget { [weak self] _ in
            try? self?.play()
            return .success
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
    }

    private func setupPlayerObservers() {
        guard let player = player else { return }

        // Add time observer for progress updates
        let interval = CMTime(seconds: 0.5, preferredTimescale: CMTimeScale(NSEC_PER_SEC))
        timeObserver = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] _ in
            guard let self = self else { return }

            // Emit progress updates
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

    private func switchToCurrentTrack() {
        guard currentIndex >= 0 && currentIndex < playerItems.count else { return }

        let wasPlaying = (status == .playing)
        let newItem = playerItems[currentIndex]

        player?.replaceCurrentItem(with: newItem)

        if let currentTrack = getCurrentTrack() {
            delegate?.playbackManager(self, trackDidChange: currentTrack, at: currentIndex)

            if wasPlaying {
                // Call playInternal directly since we're already on the playback queue
                try? playInternal()
            }

            updateNowPlayingInfo()
        }

        // Preload next track if enabled
        if preloadNext && currentIndex + 1 < playerItems.count {
            preloadTrack(at: currentIndex + 1)
        }
    }

    private func preloadTrack(at index: Int) {
        guard index >= 0 && index < playerItems.count else { return }
        // Preloading happens automatically when AVPlayerItem is created
        // We could add additional preparation here if needed
    }

    private func updateNowPlayingInfo() {
        guard let track = getCurrentTrack() else { return }

        var nowPlayingInfo: [String: Any] = [:]

        nowPlayingInfo[MPMediaItemPropertyTitle] = track.title ?? "Unknown Title"
        nowPlayingInfo[MPMediaItemPropertyArtist] = track.artist ?? "Unknown Artist"
        nowPlayingInfo[MPNowPlayingInfoPropertyElapsedPlaybackTime] = getCurrentPosition()
        nowPlayingInfo[MPMediaItemPropertyPlaybackDuration] = getDuration()
        nowPlayingInfo[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying() ? 1.0 : 0.0

        // Load artwork if available
        if let artworkUrl = track.artworkUrl, let url = URL(string: artworkUrl) {
            loadArtwork(from: url) { [weak self] image in
                if let image = image {
                    nowPlayingInfo[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
                }
                MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
            }
        } else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nowPlayingInfo
        }
    }

    private func emitStatusChange() {
        let currentTrack = getCurrentTrack()
        let currentPosition = getCurrentPosition()
        let duration = getDuration()
        let isPlaying = self.isPlaying()

        delegate?.playbackManager(
            self,
            statusChanged: currentTrack,
            at: currentIndex,
            status: status,
            currentPosition: currentPosition,
            duration: duration,
            isPlaying: isPlaying
        )
    }

    private func loadArtwork(from url: URL, completion: @escaping (UIImage?) -> Void) {
        URLSession.shared.dataTask(with: url) { data, _, _ in
            DispatchQueue.main.async {
                if let data = data, let image = UIImage(data: data) {
                    completion(image)
                } else {
                    completion(nil)
                }
            }
        }.resume()
    }

    private func cleanup() {
        // Remove observers
        if let timeObserver = timeObserver {
            player?.removeTimeObserver(timeObserver)
            self.timeObserver = nil
        }

        playerItemObservers.forEach { $0.invalidate() }
        playerItemObservers.removeAll()

        // Stop player
        player?.pause()
        player = nil
        playerItems.removeAll()
        playlist.removeAll()

        status = .idle
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
}

// MARK: - Supporting Types

enum PlaybackStatus {
    case idle
    case loading
    case playing
    case paused
    case stopped
}

struct AudioTrack {
    let id: String
    let url: String
    let title: String?
    let artist: String?
    let artworkUrl: String?
}

