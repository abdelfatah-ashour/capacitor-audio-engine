import Foundation
import AVFoundation

/**
 * Delegate protocol for playback events
 */
protocol PlaybackManagerDelegate: AnyObject {
    func playbackDidChangeStatus(_ status: String, url: String, position: Int)
    func playbackDidEncounterError(_ trackId: String, message: String)
    func playbackDidUpdateProgress(_ trackId: String, url: String, currentPosition: Int, duration: Int, isPlaying: Bool)
    func playbackDidComplete(_ trackId: String, url: String)
}

/**
 * Manages audio playback for multiple tracks with AVPlayer
 * Follows clean architecture and separation of concerns
 */
final class PlaybackManager: NSObject {

    // MARK: - Properties

    private let stateQueue = DispatchQueue(label: "audio-engine-playback-state", qos: .userInitiated)
    private weak var delegate: PlaybackManagerDelegate?

    // Track management
    private var preloadedTracks: [String: TrackInfo] = [:]

    // Current playback state
    private var currentTrackUrl: String?
    private var currentTrackId: String?
    private var currentStatus: PlaybackStatus = .idle

    // Progress monitoring
    private var progressTimer: Timer?
    private var isProgressMonitoring: Bool = false

    // Audio session observation
    private var audioSessionObserver: NSObjectProtocol?

    // MARK: - Supporting Types

    private enum PlaybackStatus {
        case idle
        case loading
        case playing
        case paused

        var stringValue: String {
            switch self {
            case .idle: return "idle"
            case .loading: return "loading"
            case .playing: return "playing"
            case .paused: return "paused"
            }
        }
    }

    private class TrackInfo {
        let url: String
        let trackId: String
        var player: AVPlayer?
        var playerItem: AVPlayerItem?
        var isLoaded: Bool = false
        var mimeType: String = ""
        var duration: Int = 0
        var size: Int64 = 0

        // KVO observations
        var statusObserver: NSKeyValueObservation?
        var durationObserver: NSKeyValueObservation?

        init(url: String, trackId: String) {
            self.url = url
            self.trackId = trackId
        }

        deinit {
            statusObserver?.invalidate()
            durationObserver?.invalidate()
        }
    }

    struct PlaybackInfo {
        let trackId: String?
        let url: String?
        let currentIndex: Int
        let currentPosition: Int
        let duration: Int
        let isPlaying: Bool
    }

    // MARK: - Initialization

    init(delegate: PlaybackManagerDelegate?) {
        self.delegate = delegate
        super.init()
        setupAudioSession()
        observeAudioSessionInterruptions()
    }

    deinit {
        if let observer = audioSessionObserver {
            NotificationCenter.default.removeObserver(observer)
        }
        destroy()
    }

    // MARK: - Public Methods

    /**
     * Preload a track from URL (supports HTTP/HTTPS URLs and local file URIs)
     */
    func preloadTrack(url: String, completion: @escaping (Result<(url: String, mimeType: String, duration: Int, size: Int64), Error>) -> Void) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            guard !url.isEmpty else {
                DispatchQueue.main.async {
                    completion(.failure(NSError(domain: "PlaybackManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid URL"])))
                }
                return
            }

            let trackId = self.generateTrackId(from: url)

            // Check if already preloaded
            if let existing = self.preloadedTracks[url], existing.isLoaded {
                DispatchQueue.main.async {
                    completion(.success((url: existing.url, mimeType: existing.mimeType, duration: existing.duration, size: existing.size)))
                }
                return
            }

            let trackInfo = TrackInfo(url: url, trackId: trackId)

            // Normalize the URL/URI to handle different formats
            let normalizedUrl = self.normalizeAudioUrl(url)
            print("[PlaybackManager] Normalized URL: \(normalizedUrl) (original: \(url))")

            // Create URL based on whether it's a remote or local file
            let audioUrl: URL
            if self.isRemoteUrl(url) {
                // For HTTP/HTTPS URLs
                guard let remoteUrl = URL(string: normalizedUrl) else {
                    DispatchQueue.main.async {
                        completion(.failure(NSError(domain: "PlaybackManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid remote URL format"])))
                    }
                    return
                }
                audioUrl = remoteUrl
            } else if url.hasPrefix("file://") {
                if let fileUrl = URL(string: url) {
                    audioUrl = fileUrl
                } else {
                    let pathString = String(url.dropFirst(7))
                    audioUrl = URL(fileURLWithPath: pathString)
                }
            } else {
                // For direct file paths or Capacitor URIs (already normalized)
                audioUrl = URL(fileURLWithPath: normalizedUrl)
            }

            let playerItem = AVPlayerItem(url: audioUrl)
            let player = AVPlayer(playerItem: playerItem)

            trackInfo.player = player
            trackInfo.playerItem = playerItem

            // Observe player item status
            trackInfo.statusObserver = playerItem.observe(\.status, options: [.new]) { [weak self, weak trackInfo] item, _ in
                guard let self = self, let trackInfo = trackInfo else { return }

                switch item.status {
                case .readyToPlay:
                    trackInfo.isLoaded = true
                    trackInfo.duration = Int(CMTimeGetSeconds(item.duration))

                    // Detect MIME type from file extension or URL
                    trackInfo.mimeType = self.detectMimeType(from: url)

                    // Try to get file size
                    if let asset = item.asset as? AVURLAsset {
                        do {
                            let resourceValues = try asset.url.resourceValues(forKeys: [.fileSizeKey])
                            trackInfo.size = Int64(resourceValues.fileSize ?? 0)
                        } catch {
                            trackInfo.size = 0
                        }
                    }

                    print("[PlaybackManager] Track preloaded successfully: \(url) (mimeType: \(trackInfo.mimeType), size: \(trackInfo.size))")
                    DispatchQueue.main.async {
                        completion(.success((url: trackInfo.url, mimeType: trackInfo.mimeType, duration: trackInfo.duration, size: trackInfo.size)))
                    }

                case .failed:
                    let error = item.error ?? NSError(domain: "PlaybackManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Unknown error"])
                    print("[PlaybackManager] Error preloading track: \(url), error: \(error)")
                    self.stateQueue.async {
                        self.preloadedTracks.removeValue(forKey: url)
                    }
                    DispatchQueue.main.async {
                        completion(.failure(error))
                    }

                case .unknown:
                    break

                @unknown default:
                    break
                }
            }

            // Observe when track completes
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemDidPlayToEndTime,
                object: playerItem,
                queue: .main
            ) { [weak self, weak trackInfo] _ in
                guard let self = self, let trackInfo = trackInfo else { return }
                print("[PlaybackManager] Track completed: \(url)")
                self.handleTrackCompletion(trackInfo)
            }

            self.preloadedTracks[url] = trackInfo
        }
    }

    /**
     * Play a track (or resume current track if no URL specified)
     */
    func playTrack(url: String?) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // If no URL specified, resume current track
            guard let targetUrl = url, !targetUrl.isEmpty else {
                if let currentUrl = self.currentTrackUrl {
                    self.resumeTrack(url: currentUrl)
                } else {
                    DispatchQueue.main.async {
                        self.delegate?.playbackDidEncounterError("", message: "No track specified and no current track")
                    }
                }
                return
            }

            // Check if track is preloaded
            guard let trackInfo = self.preloadedTracks[targetUrl], trackInfo.isLoaded else {
                DispatchQueue.main.async {
                    self.delegate?.playbackDidEncounterError(self.generateTrackId(from: targetUrl), message: "Track not preloaded: \(targetUrl)")
                }
                return
            }

            // If different track is playing, pause it first
            if let currentUrl = self.currentTrackUrl, currentUrl != targetUrl {
                self.pauseCurrentTrack()
            }

            guard let player = trackInfo.player else {
                DispatchQueue.main.async {
                    self.delegate?.playbackDidEncounterError(trackInfo.trackId, message: "Player is nil")
                }
                return
            }

            // Start playback
            player.play()
            self.currentTrackUrl = targetUrl
            self.currentTrackId = trackInfo.trackId
            self.updateStatus(.playing)
            self.startProgressMonitoring(for: trackInfo)

            print("[PlaybackManager] Started playing track: \(targetUrl)")
        }
    }

    /**
     * Pause current track or specific track
     */
    func pauseTrack(url: String?) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            let targetUrl = url ?? self.currentTrackUrl

            guard let url = targetUrl else {
                print("[PlaybackManager] No track to pause")
                return
            }

            guard let trackInfo = self.preloadedTracks[url], let player = trackInfo.player else {
                print("[PlaybackManager] Track not found or player is nil: \(url)")
                return
            }

            player.pause()
            self.updateStatus(.paused)
            self.stopProgressMonitoring()

            print("[PlaybackManager] Paused track: \(url)")
        }
    }

    /**
     * Resume current track or specific track
     */
    func resumeTrack(url: String?) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            let targetUrl = url ?? self.currentTrackUrl

            guard let url = targetUrl else {
                print("[PlaybackManager] No track to resume")
                return
            }

            guard let trackInfo = self.preloadedTracks[url], let player = trackInfo.player else {
                print("[PlaybackManager] Track not found or player is nil: \(url)")
                return
            }

            player.play()
            self.currentTrackUrl = url
            self.currentTrackId = trackInfo.trackId
            self.updateStatus(.playing)
            self.startProgressMonitoring(for: trackInfo)

            print("[PlaybackManager] Resumed track: \(url)")
        }
    }

    /**
     * Stop current track or specific track
     */
    func stopTrack(url: String?) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            let targetUrl = url ?? self.currentTrackUrl

            guard let url = targetUrl else {
                print("[PlaybackManager] No track to stop")
                return
            }

            guard let trackInfo = self.preloadedTracks[url], let player = trackInfo.player else {
                print("[PlaybackManager] Track not found or player is nil: \(url)")
                return
            }

            player.pause()
            player.seek(to: .zero)

            if self.currentTrackUrl == url {
                self.currentTrackUrl = nil
                self.currentTrackId = nil
                self.updateStatus(.idle)
                self.stopProgressMonitoring()
            }

            print("[PlaybackManager] Stopped track: \(url)")
        }
    }

    /**
     * Seek to position in track
     */
    func seekTrack(seconds: Int, url: String?) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            let targetUrl = url ?? self.currentTrackUrl

            guard let url = targetUrl else {
                print("[PlaybackManager] No track to seek")
                return
            }

            guard let trackInfo = self.preloadedTracks[url], let player = trackInfo.player else {
                print("[PlaybackManager] Track not found or player is nil: \(url)")
                return
            }

            let time = CMTime(seconds: Double(seconds), preferredTimescale: 1)
            player.seek(to: time)

            print("[PlaybackManager] Seeked track to \(seconds) seconds: \(url)")
        }
    }

    /**
     * Get current playback info
     */
    func getPlaybackInfo() -> PlaybackInfo {
        return stateQueue.sync {
            guard let url = currentTrackUrl,
                  let trackInfo = preloadedTracks[url],
                  let player = trackInfo.player else {
                return PlaybackInfo(
                    trackId: nil,
                    url: nil,
                    currentIndex: 0,
                    currentPosition: 0,
                    duration: 0,
                    isPlaying: false
                )
            }

            let currentPosition = Int(CMTimeGetSeconds(player.currentTime()))
            let duration = trackInfo.duration
            let isPlaying = player.rate > 0

            return PlaybackInfo(
                trackId: trackInfo.trackId,
                url: trackInfo.url,
                currentIndex: 0,
                currentPosition: currentPosition,
                duration: duration,
                isPlaying: isPlaying
            )
        }
    }

    /**
     * Destroy all playback resources and reinitialize
     */
    func destroy() {
        print("[PlaybackManager] Destroying playback manager")

        stateQueue.sync {
            // Stop progress monitoring
            stopProgressMonitoring()

            // Release all AVPlayer instances
            for trackInfo in preloadedTracks.values {
                trackInfo.player?.pause()
                trackInfo.player = nil
                trackInfo.playerItem = nil
                trackInfo.statusObserver?.invalidate()
                trackInfo.durationObserver?.invalidate()
            }

            // Clear all tracks
            preloadedTracks.removeAll()

            // Reset state
            currentTrackUrl = nil
            currentTrackId = nil
            currentStatus = .idle
        }

        print("[PlaybackManager] Playback manager destroyed and reinitialized")
    }

    // MARK: - Private Methods

    private func setupAudioSession() {
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default, options: [])
            try audioSession.setActive(true)
            print("[PlaybackManager] Audio session configured for playback")
        } catch {
            print("[PlaybackManager] Failed to configure audio session: \(error)")
        }
    }

    private func observeAudioSessionInterruptions() {
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
                // Interruption began - pause playback
                print("[PlaybackManager] Audio session interrupted - pausing playback")
                self.pauseCurrentTrack()

            case .ended:
                // Interruption ended - optionally resume
                if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                    let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                    if options.contains(.shouldResume) {
                        print("[PlaybackManager] Audio session interruption ended - resuming playback")
                        if let currentUrl = self.currentTrackUrl {
                            self.resumeTrack(url: currentUrl)
                        }
                    }
                }

            @unknown default:
                break
            }
        }
    }

    private func pauseCurrentTrack() {
        stateQueue.async { [weak self] in
            guard let self = self, let url = self.currentTrackUrl else { return }
            self.pauseTrack(url: url)
        }
    }

    private func updateStatus(_ newStatus: PlaybackStatus) {
        guard currentStatus != newStatus else { return }

        currentStatus = newStatus

        let statusString = newStatus.stringValue
        var position = 0

        if let url = currentTrackUrl,
           let trackInfo = preloadedTracks[url],
           let player = trackInfo.player {
            position = Int(CMTimeGetSeconds(player.currentTime()))
        }

        DispatchQueue.main.async { [weak self] in
            self?.delegate?.playbackDidChangeStatus(
                statusString,
                url: self?.currentTrackUrl ?? "",
                position: position
            )
        }
    }

    private func generateTrackId(from url: String) -> String {
        return "track_\(abs(url.hashValue))"
    }

    /**
     * Normalize audio URL/URI to handle different formats:
     * - HTTP/HTTPS URLs (CDN) - returned as-is
     * - Capacitor file URIs (capacitor://localhost/_capacitor_file_) - converted to file path
     * - file:// URIs - converted to file path
     * - Direct file paths - returned as-is
     */
    private func normalizeAudioUrl(_ url: String) -> String {
        // Handle HTTP/HTTPS URLs - return as-is
        if url.hasPrefix("http://") || url.hasPrefix("https://") {
            return url
        }

        // Handle Capacitor file URI format
        if url.contains("capacitor://localhost/_capacitor_file_") {
            return url.replacingOccurrences(of: "capacitor://localhost/_capacitor_file_", with: "")
        }

        // Handle file:// URI format
        if url.hasPrefix("file://") {
            return String(url.dropFirst(7)) // Remove "file://" prefix
        }

        // Return as-is for direct file paths
        return url
    }

    /**
     * Check if the URL is a remote URL (HTTP/HTTPS)
     */
    private func isRemoteUrl(_ url: String) -> Bool {
        return url.hasPrefix("http://") || url.hasPrefix("https://")
    }

    /**
     * Detect MIME type from file extension
     */
    private func detectMimeType(from url: String) -> String {
        // Extract file extension
        let urlString = url.lowercased()

        if urlString.hasSuffix(".m4a") {
            return "audio/m4a"
        } else if urlString.hasSuffix(".mp3") {
            return "audio/mpeg"
        } else if urlString.hasSuffix(".wav") {
            return "audio/wav"
        } else if urlString.hasSuffix(".aac") {
            return "audio/aac"
        } else if urlString.hasSuffix(".ogg") {
            return "audio/ogg"
        } else if urlString.hasSuffix(".flac") {
            return "audio/flac"
        } else if urlString.hasSuffix(".mp4") {
            return "audio/mp4"
        } else {
            return "audio/*"
        }
    }

    private func handleTrackCompletion(_ trackInfo: TrackInfo) {
        stateQueue.async { [weak self] in
            guard let self = self else { return }

            // Only emit events if this track is actually the current playing track
            guard self.currentTrackUrl == trackInfo.url else {
                return
            }

            // Stop the track completely (reset to beginning)
            if let player = trackInfo.player {
                player.pause()
                player.seek(to: .zero)
            }

            // Clear current track state
            self.currentTrackUrl = nil
            self.currentTrackId = nil

            // Update status to idle and stop monitoring
            self.updateStatus(.idle)
            self.stopProgressMonitoring()

            // Emit completion event
            DispatchQueue.main.async {
                self.delegate?.playbackDidComplete(trackInfo.trackId, url: trackInfo.url)
            }

            print("[PlaybackManager] Track completed and automatically stopped: \(trackInfo.url)")
        }
    }

    // MARK: - Progress Monitoring

    private func startProgressMonitoring(for trackInfo: TrackInfo) {
        stopProgressMonitoring()

        isProgressMonitoring = true

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.progressTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self, weak trackInfo] _ in
                guard let self = self,
                      let trackInfo = trackInfo,
                      self.isProgressMonitoring,
                      let player = trackInfo.player else {
                    return
                }

                // Only emit progress events when player is actually playing
                guard player.rate > 0 else {
                    return
                }

                let currentPosition = Int(CMTimeGetSeconds(player.currentTime()))
                let duration = trackInfo.duration

                self.delegate?.playbackDidUpdateProgress(
                    trackInfo.trackId,
                    url: trackInfo.url,
                    currentPosition: currentPosition,
                    duration: duration,
                    isPlaying: true // Always true here since we checked rate > 0 above
                )
            }
        }
    }

    private func stopProgressMonitoring() {
        isProgressMonitoring = false

        // Capture the timer to avoid forming weak reference during deallocation
        let timerToInvalidate = progressTimer
        progressTimer = nil

        DispatchQueue.main.async {
            timerToInvalidate?.invalidate()
        }
    }
}

