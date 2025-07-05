import Foundation
import Capacitor
import AVFoundation
import AVKit
import UIKit
import ObjectiveC
import UserNotifications
import Network

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */

// MARK: - Constants
private struct AudioEngineConstants {
    static let defaultSampleRate: Double = 44100.0
    static let defaultChannels = 1
    static let defaultBitrate = 128000
    static let timerInterval: TimeInterval = 1.0
    static let crossfadeDuration: TimeInterval = 0.02
    static let networkTimeout: TimeInterval = 60.0
    static let resourceTimeout: TimeInterval = 120.0
    static let networkCheckTimeout: TimeInterval = 2.0
    static let defaultFileExtension = ".m4a"
    static let mimeTypeM4A = "audio/m4a"
    static let bufferSize: AVAudioFrameCount = 512
    static let maxSegments = 2
    static let timestampMultiplier = 1000.0
    static let durationRoundingFactor = 10.0
}

@objc(CapacitorAudioEnginePlugin)
public class CapacitorAudioEnginePlugin: CAPPlugin, CAPBridgedPlugin, AVAudioPlayerDelegate {
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
        CAPPluginMethod(name: "addListener", returnType: CAPPluginReturnCallback),
        CAPPluginMethod(name: "removeAllListeners", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "preload", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "startPlayback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "pausePlayback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "resumePlayback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopPlayback", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "seekTo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPlaybackStatus", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getAudioInfo", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "clearPreloadedAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "getPreloadedAudio", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isAudioPreloaded", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "destroyAllPlaybacks", returnType: CAPPluginReturnPromise),
    ]

    // MARK: - Interruption monitoring properties
    private var interruptionObservers: [NSObjectProtocol] = []
    private var hasInterruptionListeners = false

    private var durationTimer: Timer?
    private var durationDispatchSource: DispatchSourceTimer?
    private var audioRecorder: AVAudioRecorder?
    private var recordingSession: AVAudioSession?
    private var isRecording = false
    private var recordingPath: URL?
    private var exportSession: AVAssetExportSession?
    private var wasRecordingBeforeInterruption = false
    private var lastReportedDuration: Double?
    private var currentDuration: Double = 0

    // Segment rolling properties
    private var maxDuration: Int?
    private var segmentTimer: Timer?
    private var segmentFiles = [URL]()
    private var currentSegment = 0

    // Playback properties
    private var audioPlayer: AVAudioPlayer?
    private var isPlaying = false
    private var currentPlaybackPath: String?
    private var playbackProgressTimer: Timer?
    private var playbackSpeed: Float = 1.0
    private var playbackVolume: Float = 1.0
    private var isLooping = false
    private var urlSessionTask: URLSessionDataTask?

    // Preloaded audio storage
    private var preloadedAudioPlayers: [String: AVAudioPlayer] = [:]
    private var preloadedAudioData: [String: Data] = [:]

    // Network monitoring
    private var networkMonitor: NWPathMonitor?
    private var isNetworkAvailable = true

    // MARK: - Thread Safety

    private let stateQueue = DispatchQueue(label: "audio-engine-state", qos: .userInteractive)

    private func performStateOperation<T>(_ operation: () throws -> T) rethrows -> T {
        return try stateQueue.sync { try operation() }
    }

    // MARK: - Logging Utility

    private func log(_ message: String) {
        #if DEBUG
        print("[AudioEngine] \(message)")
        #endif
    }

    // MARK: - Network Utility Methods

    private func checkNetworkAvailability() -> Bool {
        if #available(iOS 12.0, *) {
            let monitor = NWPathMonitor()
            var isConnected = false
            let semaphore = DispatchSemaphore(value: 0)

            monitor.pathUpdateHandler = { path in
                isConnected = path.status == .satisfied
                semaphore.signal()
            }

            let queue = DispatchQueue(label: "NetworkMonitor")
            monitor.start(queue: queue)

            _ = semaphore.wait(timeout: .now() + AudioEngineConstants.networkCheckTimeout) // 2 second timeout
            monitor.cancel()

            return isConnected
        } else {
            // Fallback for iOS < 12
            return true // Assume network is available
        }
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
        request.timeoutInterval = AudioEngineConstants.networkTimeout // Increased timeout for large files

        // Add range header for partial content support (useful for large files)
        // This allows for progressive download and better error recovery
        request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")

        return request
    }

    @objc func checkPermission(_ call: CAPPluginCall) {
        let audioStatus = AVAudioSession.sharedInstance().recordPermission
        let audioGranted = audioStatus == .granted

        // Check notification permission status on iOS 10+
        var notificationGranted = true
        if #available(iOS 10.0, *) {
            let semaphore = DispatchSemaphore(value: 0)
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                notificationGranted = settings.authorizationStatus == .authorized
                semaphore.signal()
            }
            semaphore.wait()
        }

        call.resolve([
            "granted": audioGranted && notificationGranted,
            "audioPermission": audioGranted,
            "notificationPermission": notificationGranted
        ])
    }

    override public func load() {
        startInterruptionMonitoring()
    }

    // MARK: - Interruption monitoring methods

    public func startInterruptionMonitoring() {
        hasInterruptionListeners = true
        log("Starting interruption monitoring")

        // Audio session interruption notifications
        let interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            self?.log("Received interruption notification")
            self?.handleInterruption(notification)
        }
        interruptionObservers.append(interruptionObserver)

        // Audio route change notifications
        let routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            self?.log("Received route change notification")
            self?.handleRouteChange(notification)
        }
        interruptionObservers.append(routeChangeObserver)
    }

    public func stopInterruptionMonitoring() {
        log("Stopping interruption monitoring")
        hasInterruptionListeners = false
        interruptionObservers.forEach { NotificationCenter.default.removeObserver($0) }
        interruptionObservers.removeAll()
    }

    private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            log("Failed to get interruption type")
            return
        }

        log("Handling interruption type: \(type)")

        switch type {
        case .began:
            handleInterruptionBegan()
            notifyListeners("recordingInterruption", data: [
                "message": "Interruption began - audio session interrupted"
            ])

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                handleInterruptionEnded(shouldResume: options.contains(.shouldResume))

                if options.contains(.shouldResume) {
                    notifyListeners("recordingInterruption", data: [
                        "message": "Interruption ended - audio session resumed"
                    ])
                } else {
                    notifyListeners("recordingInterruption", data: [
                        "message": "Interruption ended - manual resume required"
                    ])
                }
            }
        @unknown default:
            break
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        var message = "Audio route changed: "

        switch reason {
        case .oldDeviceUnavailable:
            message += "headphones unplugged"
        case .newDeviceAvailable:
            message += "new device connected"
        case .categoryChange:
            message += "category changed"
        case .override:
            message += "route overridden"
        case .wakeFromSleep:
            message += "device woke from sleep"
        case .noSuitableRouteForCategory:
            message += "no suitable route for category"
        case .routeConfigurationChange:
            message += "configuration changed"
        default:
            message += "unknown reason"
        }

        notifyListeners("recordingInterruption", data: [
            "message": message
        ])
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

    // Helper method to get recorder settings with default values
    private func getRecorderSettings() -> [String: Any] {
        let settings = audioRecorder?.settings ?? [:]

        // Ensure default values are provided if settings are missing
        var result: [String: Any] = [:]
        result[AVSampleRateKey] = settings[AVSampleRateKey] as? Double ?? AudioEngineConstants.defaultSampleRate
        result[AVNumberOfChannelsKey] = settings[AVNumberOfChannelsKey] as? Int ?? AudioEngineConstants.defaultChannels
        result[AVEncoderBitRateKey] = settings[AVEncoderBitRateKey] as? Int ?? AudioEngineConstants.defaultBitrate

        return result
    }

    // Helper method to get sample rate from settings
    private func getSampleRate() -> Double {
        return getRecorderSettings()[AVSampleRateKey] as? Double ?? AudioEngineConstants.defaultSampleRate
    }

    // Helper method to get channels from settings
    private func getChannels() -> Int {
        return getRecorderSettings()[AVNumberOfChannelsKey] as? Int ?? AudioEngineConstants.defaultChannels
    }

    // Helper method to get bitrate from settings
    private func getBitrate() -> Int {
        return getRecorderSettings()[AVEncoderBitRateKey] as? Int ?? AudioEngineConstants.defaultBitrate
    }

    // Helper method to create recording response
    private func createRecordingResponse(fileToReturn: URL, fileSize: Int64, modificationDate: Date, durationInSeconds: Double) -> [String: Any] {
        // Get settings using helper methods
        let sampleRate = getSampleRate()
        let channels = getChannels()
        let bitrate = getBitrate()

        // Round to 1 decimal place for better display
        let roundedDuration = round(durationInSeconds * AudioEngineConstants.durationRoundingFactor) / AudioEngineConstants.durationRoundingFactor

        // Encode audio file to base64 with MIME prefix (Data URI format)
        var base64Audio: String?
        do {
            let audioData = try Data(contentsOf: fileToReturn)
            let base64String = audioData.base64EncodedString()
            base64Audio = "data:audio/m4a;base64," + base64String
        } catch {
            log("Failed to encode audio file to base64: \(error.localizedDescription)")
            base64Audio = nil
        }

        return [
            "path": fileToReturn.path,
            "uri": fileToReturn.absoluteString,
            "webPath": "capacitor://localhost/_capacitor_file_" + fileToReturn.path,
            "mimeType": AudioEngineConstants.mimeTypeM4A, // M4A container with AAC audio
            "size": fileSize,
            "duration": roundedDuration,
            "sampleRate": Int(sampleRate), // Cast to Int for consistency
            "channels": channels,
            "bitrate": bitrate,
            "createdAt": Int(modificationDate.timeIntervalSince1970 * AudioEngineConstants.timestampMultiplier), // Milliseconds timestamp
            "filename": fileToReturn.lastPathComponent,
            "base64": base64Audio ?? ""
        ]
    }

    private func startDurationMonitoring() {
        // Stop any existing timer
        stopDurationMonitoring()

        log("Starting duration monitoring")

        // Use dispatch queue for more reliable timing
        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        // Configure timer to fire every 1000ms (1 second)
        dispatchSource.schedule(deadline: .now(), repeating: .seconds(Int(AudioEngineConstants.timerInterval)))

        // Set event handler
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self,
                  let recorder = self.audioRecorder,
                  self.isRecording else { return }

            // Get current time
            let duration = max(0, recorder.currentTime)

            // Increment currentDuration by 1 second each time the timer fires
            // This ensures consistent duration tracking across segments
            self.currentDuration += AudioEngineConstants.timerInterval

            self.lastReportedDuration = duration

            log("[iOS] Duration changed: \(duration) seconds, currentDuration: \(self.currentDuration) seconds")

            // Must dispatch to main queue for UI updates
            DispatchQueue.main.async {
                // Convert to integer by truncating decimal places
                let integerDuration = Int(self.currentDuration)
                self.log("Emitting durationChange event with integer duration: \(integerDuration)")
                self.notifyListeners("durationChange", data: [
                    "duration": integerDuration
                ])
            }
        }

        // Store the dispatch source and start it
        self.durationDispatchSource = dispatchSource
        dispatchSource.resume()

        log("Duration monitoring started with dispatch source timer")
    }

    private func stopDurationMonitoring() {
        log("Stopping duration monitoring")

        // Clean up timer (if using Timer)
        durationTimer?.invalidate()
        durationTimer = nil

        // Clean up dispatch source (if using DispatchSourceTimer)
        if let dispatchSource = durationDispatchSource {
            dispatchSource.cancel()
            durationDispatchSource = nil
        }

        lastReportedDuration = nil
    }

    private func stopSegmentTimer() {
        log("Stopping segment timer")

        // Get associated dispatch source
        if let timer = segmentTimer,
           let dispatchSource = objc_getAssociatedObject(timer, "dispatchSource") as? DispatchSourceTimer {
            dispatchSource.cancel()
        }

        // Clean up timer
        segmentTimer?.invalidate()
        segmentTimer = nil

        log("Segment timer stopped")
    }

    private func startPlaybackProgressTimer() {
        // Stop any existing timer
        stopPlaybackProgressTimer()

        // Ensure timer is created on main thread
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            log("Starting playback progress timer")

            // Start a new timer that fires every 1 second (matching Android)
            self.playbackProgressTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
                guard let self = self, let player = self.audioPlayer, self.isPlaying else {
                    return
                }

                // Calculate position percentage (0-100)
                let currentTime = player.currentTime
                let duration = player.duration
                let position = duration > 0 ? (currentTime / duration) * 100 : 0

                self.log("Playback progress: \(currentTime)/\(duration) (\(position)%)")

                // Notify listeners of progress
                let eventData: [String: Any] = [
                    "currentTime": currentTime,
                    "duration": duration,
                    "position": position
                ]
                self.notifyListeners("playbackProgress", data: eventData)
            }
        }
    }

    private func stopPlaybackProgressTimer() {
        log("Stopping playback progress timer")
        DispatchQueue.main.async { [weak self] in
            self?.playbackProgressTimer?.invalidate()
            self?.playbackProgressTimer = nil
        }
    }

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

        if let maxDuration = call.getInt("maxDuration"), maxDuration < 0 {
            call.reject("Invalid maxDuration: must be non-negative")
            return
        }

        if audioRecorder != nil {
            call.reject("Recording is already in progress")
            return
        }

        // Reset segment tracking
        segmentFiles.removeAll()
        currentSegment = 0

        // Reset duration counter when starting a new recording
        currentDuration = 0

        // Notify listeners of the duration change
        notifyListeners("durationChange", data: [
            "duration": currentDuration
        ])

        // Get maxDuration parameter (optional)
        self.maxDuration = call.getInt("maxDuration")

        // --- Enforce .m4a format ---
        let sampleRate = call.getInt("sampleRate") ?? Int(AudioEngineConstants.defaultSampleRate)
        let channels = call.getInt("channels") ?? AudioEngineConstants.defaultChannels
        let bitrate = call.getInt("bitrate") ?? AudioEngineConstants.defaultBitrate
        let fileManager = FileManager.default
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsPath = documentsPath.appendingPathComponent("Recordings", isDirectory: true)

        do {
            try fileManager.createDirectory(at: recordingsPath, withIntermediateDirectories: true, attributes: nil)
        } catch let dirError as NSError {
            call.reject("Failed to create recordings directory: \(dirError.localizedDescription). Code: \(dirError.code)")
            return
        }

        // Setup audio session
        do {
            startInterruptionMonitoring()
            recordingSession = AVAudioSession.sharedInstance()
            // Enhanced configuration for background audio recording with .mixWithOthers
            try recordingSession?.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .mixWithOthers])
            try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)
            try recordingSession?.overrideOutputAudioPort(.speaker)
        } catch let error as NSError {
            let err: [String: Any] = ["message": "Failed to setup audio session: \(error.localizedDescription)", "code": error.code]
            notifyListeners("error", data: err)
            call.reject("Failed to setup audio session: \(error.localizedDescription). Code: \(error.code)")
            return
        }

        if let maxDuration = maxDuration, maxDuration > 0 {
            // Start with segment rolling enabled
            startNextSegment(recordingsPath: recordingsPath, sampleRate: sampleRate, channels: channels, bitrate: bitrate)
            startSegmentTimer(maxDuration: maxDuration)
            isRecording = true
            startDurationMonitoring()
            call.resolve()
        } else {
            // Regular recording without segments
            // Set maxDuration to nil to ensure no segment rolling happens
            self.maxDuration = nil

            let ext = AudioEngineConstants.defaultFileExtension
            let timestamp = Int(Date().timeIntervalSince1970 * AudioEngineConstants.timestampMultiplier)
            let audioFilename = recordingsPath.appendingPathComponent("recording_\(timestamp)\(ext)")
            recordingPath = audioFilename

            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channels,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
                AVEncoderBitRateKey: bitrate
            ]

            do {
                audioRecorder = try AVAudioRecorder(url: audioFilename, settings: settings)
                guard let recorder = audioRecorder else {
                    call.reject("Failed to initialize AVAudioRecorder")
                    return
                }
                if !recorder.prepareToRecord() {
                    call.reject("Failed to prepare AVAudioRecorder")
                    return
                }
                let recordingStarted = recorder.record()
                if recordingStarted {
                    isRecording = true
                    startDurationMonitoring()
                    call.resolve()
                } else {
                    call.reject("Failed to start AVAudioRecorder recording")
                }
            } catch let error as NSError {
                let err: [String: Any] = ["message": "Failed to start recording: \(error.localizedDescription)", "code": error.code]
                notifyListeners("error", data: err)
                call.reject("Failed to start recording: \(error.localizedDescription). Code: \(error.code)")
            }
        }
    }

    private func startNextSegment(recordingsPath: URL, sampleRate: Int, channels: Int, bitrate: Int) {
        log("Starting next segment. Current segment count: \(segmentFiles.count)")

        // Stop current recorder if exists
        if let recorder = audioRecorder, recorder.isRecording {
            log("Stopping current recorder")
            recorder.stop()
        }

        // Create new segment file
        let ext = AudioEngineConstants.defaultFileExtension
        let timestamp = Int(Date().timeIntervalSince1970 * AudioEngineConstants.timestampMultiplier)
        let segmentFilename = recordingsPath.appendingPathComponent("segment_\(timestamp)\(ext)")
        log("Creating new segment file: \(segmentFilename.lastPathComponent)")

        // Add to segments list
        performStateOperation {
            segmentFiles.append(segmentFilename)
            currentSegment += 1
        }
        log("Current segment number: \(currentSegment)")

        // Keep only last 2 segments
        performStateOperation {
            while segmentFiles.count > AudioEngineConstants.maxSegments {
                let oldSegmentURL = segmentFiles.removeFirst()
                try? FileManager.default.removeItem(at: oldSegmentURL)
                log("Removed old segment: \(oldSegmentURL.lastPathComponent)")
            }
        }

        // Set as current recording path
        recordingPath = segmentFilename

        // Setup recording settings
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: channels,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVEncoderBitRateKey: bitrate
        ]

        do {
            // Create and start new recorder
            audioRecorder = try AVAudioRecorder(url: segmentFilename, settings: settings)
            guard let recorder = audioRecorder else {
                let errorMsg = "Failed to initialize segment recorder"
                log(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
                return
            }

            if !recorder.prepareToRecord() {
                let errorMsg = "Failed to prepare segment recorder"
                log(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
                return
            }

            let recordingStarted = recorder.record()
            if !recordingStarted {
                let errorMsg = "Failed to start segment recording"
                log(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
            } else {
                log("Successfully started recording segment: \(segmentFilename.lastPathComponent)")
            }
        } catch {
            let errorMsg = "Failed to create segment recorder: \(error.localizedDescription)"
            log(errorMsg)
            notifyListeners("error", data: ["message": errorMsg])
        }
    }

    private func startSegmentTimer(maxDuration: Int) {
        // Cancel existing timer
        segmentTimer?.invalidate()

        log("Starting segment timer with maxDuration: \(maxDuration) seconds")

        // Use dispatch queue for more reliable timing
        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        // Configure timer to fire every maxDuration seconds
        dispatchSource.schedule(deadline: .now() + .seconds(maxDuration), repeating: .seconds(maxDuration))

        // Set event handler
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self, self.isRecording else {
                self?.log("Timer fired but recording is not active, ignoring")
                return
            }

            self.log("Segment timer fired after \(maxDuration) seconds")

            // Execute on main thread since we're modifying UI-related state
            DispatchQueue.main.async {
                // Get current recording settings
                guard let recorder = self.audioRecorder else {
                    self.log("Timer fired but recorder is nil, ignoring")
                    return
                }

                let settings = recorder.settings
                let sampleRate = settings[AVSampleRateKey] as? Int ?? 44100
                let channels = settings[AVNumberOfChannelsKey] as? Int ?? 1
                let bitrate = settings[AVEncoderBitRateKey] as? Int ?? 128000

                // Get recordings path
                let fileManager = FileManager.default
                let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let recordingsPath = documentsPath.appendingPathComponent("Recordings", isDirectory: true)

                self.log("Creating next segment after timer interval")
                // Start next segment
                self.startNextSegment(recordingsPath: recordingsPath, sampleRate: sampleRate, channels: channels, bitrate: bitrate)
            }
        }

        // Store timer and start it
        self.segmentTimer?.invalidate()
        self.segmentTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: false) { _ in
            // Just a dummy timer to keep a reference
        }

        // Start the dispatch source
        dispatchSource.resume()

        // Keep a reference to the dispatch source using associated objects
        if let timer = self.segmentTimer {
            objc_setAssociatedObject(timer, "dispatchSource", dispatchSource, .OBJC_ASSOCIATION_RETAIN)
        }

        log("Segment timer started successfully")
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        if let recorder = audioRecorder, isRecording { // isRecording is the plugin's overall session state
            if recorder.isRecording { // Check if it's actually recording (not already paused)
                log("Pausing recording")

                // Pause the segment timer when recording is paused
                stopSegmentTimer()

                // Stop duration monitoring when recording is paused
                stopDurationMonitoring()

                recorder.pause()
                // isRecording remains true, recorder.isRecording becomes false after pause()
                call.resolve()
            } else {
                // It's "isRecording" (session active) but not "recorder.isRecording" (already paused)
                call.reject("Recording is already paused.")
            }
        } else {
            // Neither session active nor recorder exists
            call.reject("No active recording session to pause.")
        }
    }

    @objc func resumeRecording(_ call: CAPPluginCall) {
        guard let recorder = audioRecorder, isRecording else {
            call.reject("No recording session active or recorder not initialized.")
            return
        }

        if recorder.isRecording {
            call.reject("Recording is already active, not paused.")
            return
        }

        log("Resuming recording")

        // If it's paused (plugin's isRecording is true, but recorder.isRecording is false)
        do {
            // Ensure audio session is active
            try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

            let recordingResumed = recorder.record() // AVAudioRecorder's record method resumes if paused
            if recordingResumed {
                // recorder.isRecording will now be true

                // Restart segment timer if maxDuration is set
                if let maxDuration = maxDuration, maxDuration > 0 {
                    log("Restarting segment timer after resume")
                    startSegmentTimer(maxDuration: maxDuration)
                }

                // Restart duration monitoring when recording is resumed
                startDurationMonitoring()

                call.resolve()
            } else {
                call.reject("Failed to resume recording.")
            }
        } catch {
            call.reject("Failed to set audio session active for resume: \(error.localizedDescription)")
        }
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        guard let recorder = audioRecorder, isRecording else { // isRecording is the plugin's overall session state
            call.reject("No active recording to stop.")
            return
        }

        // Stop duration monitoring
        stopDurationMonitoring()

        // Don't reset currentDuration here to maintain total duration from start to stop

        // Stop monitoring for interruptions
        stopInterruptionMonitoring()

        // Stop segment timer if active
        stopSegmentTimer()

        // Stop the actual AVAudioRecorder
        recorder.stop()
        isRecording = false // Update plugin's session state

        // Get the file to return (last segment or regular recording)
        guard let path = recordingPath else {
            call.reject("Recording path not found after stopping.")
            return
        }

        // Determine which file to return
        var fileToReturn = path

        // If using segments and we have segments
        if let maxDuration = maxDuration, maxDuration > 0, !segmentFiles.isEmpty {
            log("Processing segments. Total segments: \(segmentFiles.count)")

            // Check if we have at least 2 segments and the last segment is shorter than maxDuration
            if segmentFiles.count >= 2 {
                guard let lastSegment = segmentFiles.last,
                      segmentFiles.count >= 2 else {
                    log("Insufficient segments for merging")
                    return
                }

                let previousSegment = segmentFiles[segmentFiles.count - 2]

                // Get duration of the last segment
                let lastSegmentAsset = AVAsset(url: lastSegment)
                let lastSegmentDuration = lastSegmentAsset.duration.seconds

                log("Last segment duration: \(lastSegmentDuration) seconds, maxDuration: \(maxDuration) seconds")

                // If last segment is shorter than maxDuration, merge with previous segment
                if lastSegmentDuration < Double(maxDuration) {
                    log("Last segment is shorter than maxDuration, merging with previous segment")

                    // Create a new file for the merged audio
                    let fileManager = FileManager.default
                    let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
                    let recordingsPath = documentsPath.appendingPathComponent("Recordings", isDirectory: true)
                    let timestamp = Int(Date().timeIntervalSince1970 * 1000)
                    let mergedFilePath = recordingsPath.appendingPathComponent("merged_\(timestamp).m4a")

                    // Process the segments asynchronously
                    // Calculate how much time we need from the previous segment (in seconds)
                    // Use ceiling to ensure we get at least the full duration requested
                    let requiredDuration = ceil(Double(maxDuration) - lastSegmentDuration)
                    log("Required duration from previous segment: \(requiredDuration) seconds")

                    // Get previous segment duration to validate
                    let previousSegmentAsset = AVAsset(url: previousSegment)
                    let previousSegmentDuration = previousSegmentAsset.duration.seconds

                    // Make sure we don't request more than what's available
                    let validRequiredDuration = min(requiredDuration, previousSegmentDuration)
                    log("Valid required duration: \(validRequiredDuration) seconds (previous segment duration: \(previousSegmentDuration) seconds)")

                    // Merge the audio files asynchronously
                    mergeAudioFiles(firstFile: previousSegment, secondFile: lastSegment, outputFile: mergedFilePath, requiredDuration: validRequiredDuration) { result in
                        switch result {
                        case .success:
                            fileToReturn = mergedFilePath
                            self.log("Successfully merged segments into: \(mergedFilePath.path)")

                            // Clean up the segments that were merged
                            try? FileManager.default.removeItem(at: previousSegment)
                            try? FileManager.default.removeItem(at: lastSegment)
                            self.log("Removed original segments after merging")

                            // Remove the merged segments from the list
                            self.segmentFiles.removeAll { $0 == previousSegment || $0 == lastSegment }

                            // Add the merged file to the list
                            self.segmentFiles.append(mergedFilePath)

                        case .failure(let error):
                            self.log("Failed to merge audio segments: \(error.localizedDescription)")
                            fileToReturn = lastSegment
                        }

                        // Clean up other segments
                        for segmentPath in self.segmentFiles {
                            if segmentPath != fileToReturn {
                                try? FileManager.default.removeItem(at: segmentPath)
                                self.log("Removed unused segment: \(segmentPath.lastPathComponent)")
                            }
                        }
                        self.segmentFiles.removeAll()
                        self.log("Final recording file: \(fileToReturn.path)")

                        // Reset segment tracking
                        self.maxDuration = nil
                        self.currentSegment = 0

                        // Process the final file and resolve the call
                        do {
                            // Get file attributes
                            let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileToReturn.path)
                            let fileSize = fileAttributes[.size] as? Int64 ?? 0
                            let modificationDate = fileAttributes[.modificationDate] as? Date ?? Date() // Use modificationDate

                            // Get audio metadata
                            let asset = AVAsset(url: fileToReturn)
                            let durationInSeconds = asset.duration.seconds

                            self.log("Original recording duration: \(durationInSeconds) seconds")

                            self.log("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

                            // Use the helper method to create response with base64 encoding
                            let response = self.createRecordingResponse(
                                fileToReturn: fileToReturn,
                                fileSize: fileSize,
                                modificationDate: modificationDate,
                                durationInSeconds: durationInSeconds
                            )
                            call.resolve(response)
                        } catch {
                            call.reject("Failed to get recording info: \(error.localizedDescription)")
                        }

                        // Cleanup
                        self.audioRecorder = nil // Recorder is nilled out after use
                        self.recordingPath = nil // Clear the path as well
                    }

                    // Return early - the call will be resolved in the completion handler
                    return
                } else {
                    log("Last segment is not shorter than maxDuration, using as is")
                    fileToReturn = lastSegment

                    // Process the file asynchronously to maintain consistent pattern
                    DispatchQueue.global(qos: .userInitiated).async {
                        // Clean up other segments
                        for segmentPath in self.segmentFiles {
                            if segmentPath != fileToReturn {
                                try? FileManager.default.removeItem(at: segmentPath)
                                self.log("Removed unused segment: \(segmentPath.lastPathComponent)")
                            }
                        }
                        self.segmentFiles.removeAll()
                        self.log("Final recording file: \(fileToReturn.path)")

                        // Reset segment tracking
                        self.maxDuration = nil
                        self.currentSegment = 0

                        // Process the final file and resolve the call
                        do {
                            // Get file attributes
                            let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileToReturn.path)
                            let fileSize = fileAttributes[.size] as? Int64 ?? 0
                            let modificationDate = fileAttributes[.modificationDate] as? Date ?? Date() // Use modificationDate

                            // Get audio metadata
                            let asset = AVAsset(url: fileToReturn)
                            let durationInSeconds = asset.duration.seconds

                            self.log("Original recording duration: \(durationInSeconds) seconds")

                            self.log("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

                            // Use the helper method to create response with base64 encoding
                            let response = self.createRecordingResponse(
                                fileToReturn: fileToReturn,
                                fileSize: fileSize,
                                modificationDate: modificationDate,
                                durationInSeconds: durationInSeconds
                            )
                            call.resolve(response)
                        } catch {
                            call.reject("Failed to get recording info: \(error.localizedDescription)")
                        }

                        // Cleanup
                        self.audioRecorder = nil // Recorder is nilled out after use
                        self.recordingPath = nil // Clear the path as well
                    }

                    // Return early - the call will be resolved in the async block
                    return
                }
            } else {
                log("Only one segment available, using it as is")
                fileToReturn = segmentFiles.last ?? path

                // Clean up other segments and reset state
                cleanupSegmentFiles(except: fileToReturn)
                resetRecordingState()

                // Process the final file and resolve the call
                processRecordingFile(fileToReturn, call: call)
                return
            }
        }
    }

    // MARK: - Helper Methods for Recording

    private func processRecordingFile(_ fileToReturn: URL, call: CAPPluginCall) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // Get file attributes
                let fileAttributes = try FileManager.default.attributesOfItem(atPath: fileToReturn.path)
                let fileSize = fileAttributes[.size] as? Int64 ?? 0
                let modificationDate = fileAttributes[.modificationDate] as? Date ?? Date()

                // Get audio metadata
                let asset = AVAsset(url: fileToReturn)
                let durationInSeconds = asset.duration.seconds

                self.log("Original recording duration: \(durationInSeconds) seconds")
                self.log("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

                // Use the helper method to create response with base64 encoding
                let response = self.createRecordingResponse(
                    fileToReturn: fileToReturn,
                    fileSize: fileSize,
                    modificationDate: modificationDate,
                    durationInSeconds: durationInSeconds
                )

                DispatchQueue.main.async {
                    call.resolve(response)
                }
            } catch {
                DispatchQueue.main.async {
                    call.reject("Failed to get recording info: \(error.localizedDescription)")
                }
            }

            // Cleanup
            self.audioRecorder = nil
            self.recordingPath = nil
        }
    }

    private func cleanupSegmentFiles(except keepFile: URL? = nil) {
        performStateOperation {
            for segmentPath in segmentFiles {
                if let keepFile = keepFile, segmentPath == keepFile {
                    continue
                }
                try? FileManager.default.removeItem(at: segmentPath)
                log("Removed unused segment: \(segmentPath.lastPathComponent)")
            }
            segmentFiles.removeAll()
            if let keepFile = keepFile {
                segmentFiles.append(keepFile)
            }
        }
    }

    private func resetRecordingState() {
        maxDuration = nil
        currentSegment = 0
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        if isRecording || audioRecorder != nil {
            // Return the current duration as an integer
            // Make sure we're returning the most up-to-date duration value
            let intDuration = Int(currentDuration)
            log("getDuration returning: \(intDuration) seconds")
            call.resolve([
                "duration": intDuration
            ])
        } else {
            call.reject("No active recording")
        }
    }

    @objc func getStatus(_ call: CAPPluginCall) {
        let currentStatus: String
        let sessionActive = self.isRecording // Plugin's flag for active session

        if let recorder = audioRecorder, sessionActive {
            if recorder.isRecording {
                currentStatus = "recording" // Actively capturing audio
            } else {
                currentStatus = "paused"    // Session active, but paused
            }
        } else {
            currentStatus = "idle"          // No active session, or recorder not initialized
        }

        // Use the current duration that's being incremented by our timer
        call.resolve([
            "status": currentStatus, // "idle", "recording", "paused"
            "isRecording": sessionActive && (currentStatus == "recording" || currentStatus == "paused"),
            "currentSegment": currentSegment,
            "duration": Int(currentDuration)
        ])
    }

    @objc func trimAudio(_ call: CAPPluginCall) {
        guard let sourcePath = call.getString("uri"), !sourcePath.isEmpty else {
            call.reject("Source URI is required and cannot be empty")
            return
        }

        let startTime = call.getDouble("start") ?? 0.0
        let endTime = call.getDouble("end") ?? 0.0

        // Input validation
        if startTime < 0 {
            call.reject("Start time must be non-negative")
            return
        }

        if endTime <= startTime {
            call.reject("End time must be greater than start time")
            return
        }

        if endTime - startTime > 3600 { // 1 hour limit
            call.reject("Trim duration cannot exceed 1 hour")
            return
        }

        // Setup audio session
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.playback, mode: .default)
            try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            call.reject("Failed to setup audio session: \(error.localizedDescription)")
            return
        }

        // Convert source path to URL
        var sourceURL: URL

        // Handle Capacitor file URI format
        if sourcePath.contains("capacitor://localhost/_capacitor_file_") {
            // Extract the actual file path from the Capacitor URI
            let pathComponents = sourcePath.components(separatedBy: "_capacitor_file_")
            if pathComponents.count > 1 {
                sourceURL = URL(fileURLWithPath: pathComponents[1])
            } else {
                call.reject("Invalid Capacitor file URI format")
                return
            }
        } else if sourcePath.hasPrefix("file://") {
            guard let url = URL(string: sourcePath) else {
                call.reject("Invalid file URL format")
                return
            }
            sourceURL = url
        } else {
            sourceURL = URL(fileURLWithPath: sourcePath)
        }

        // Verify source file exists
        guard FileManager.default.fileExists(atPath: sourceURL.path) else {
            call.reject("Source audio file does not exist at path: \(sourceURL.path)")
            return
        }

        // Create output directory in documents
        let fileManager = FileManager.default
        let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let trimmedPath = documentsPath.appendingPathComponent("Trimmed", isDirectory: true)

        do {
            try fileManager.createDirectory(at: trimmedPath, withIntermediateDirectories: true, attributes: nil)

            // Create output file URL
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let outputURL = trimmedPath.appendingPathComponent("trimmed_\(timestamp).m4a")

            // Remove output file if it already exists
            if fileManager.fileExists(atPath: outputURL.path) {
                try fileManager.removeItem(at: outputURL)
            }

            // Load the audio asset
            let asset = AVAsset(url: sourceURL)

            // Validate asset
            let duration = asset.duration.seconds
            guard duration > 0 else {
                call.reject("Invalid audio file duration")
                return
            }

            // Create export session with more detailed error handling
            guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                call.reject("Failed to create export session - asset may be invalid")
                return
            }

            // Store strong reference to export session
            self.exportSession = session

            // Validate export session
            guard session.supportedFileTypes.contains(.m4a) else {
                call.reject("M4A export is not supported for this audio file")
                return
            }

            // Configure the export with validated time range
            let validatedStartTime = max(0, min(startTime, duration))
            let validatedEndTime = min(endTime, duration)

            session.outputURL = outputURL
            session.outputFileType = .m4a
            session.timeRange = CMTimeRange(
                start: CMTime(seconds: validatedStartTime, preferredTimescale: 1000),
                end: CMTime(seconds: validatedEndTime, preferredTimescale: 1000)
            )

            // Perform the export with detailed error reporting
            session.exportAsynchronously { [weak self] in
                DispatchQueue.main.async {
                    guard let self = self else {
                        call.reject("Plugin instance was deallocated")
                        return
                    }

                    guard let exportSession = self.exportSession else {
                        call.reject("Export session was deallocated")
                        return
                    }

                    switch exportSession.status {
                    case .completed:
                        do {
                            // Verify the output file exists and has size
                            guard fileManager.fileExists(atPath: outputURL.path) else {
                                call.reject("Export completed but output file not found")
                                return
                            }

                            let fileAttributes = try FileManager.default.attributesOfItem(atPath: outputURL.path)
                            let fileSize = fileAttributes[.size] as? Int64 ?? 0

                            guard fileSize > 0 else {
                                call.reject("Export completed but file is empty")
                                return
                            }

                            // Create a temporary directory URL that's accessible via web
                            let tempDir = FileManager.default.temporaryDirectory
                            let tempFile = tempDir.appendingPathComponent(outputURL.lastPathComponent)

                            // Copy the file to temporary directory
                            if FileManager.default.fileExists(atPath: tempFile.path) {
                                try FileManager.default.removeItem(at: tempFile)
                            }
                            try FileManager.default.copyItem(at: outputURL, to: tempFile)                            // Get audio metadata
                            let trimmedAsset = AVAsset(url: outputURL)
                            let duration = trimmedAsset.duration.seconds

                            // Convert to integer for fixed display
                            let integerDuration = Int(duration)

                            // Retrieve settings used for recording. Recorder instance is still valid here.
                            let currentSettings = self.audioRecorder?.settings ?? [:]

                            // Encode audio file to base64 with MIME prefix (Data URI format)
                            var base64Audio: String?
                            do {
                                let audioData = try Data(contentsOf: outputURL)
                                let base64String = audioData.base64EncodedString()
                                base64Audio = "data:audio/m4a;base64," + base64String
                            } catch {
                                self.log("Failed to encode trimmed audio file to base64: \(error.localizedDescription)")
                                base64Audio = nil
                            }

                            // Create response object
                            let response: [String: Any] = [
                                "path": outputURL.path,
                                "uri": tempFile.absoluteString, // URI of the accessible temp file
                                "webPath": "capacitor://localhost/_capacitor_file_" + tempFile.path,
                                "mimeType": "audio/m4a",
                                "size": fileSize,
                                "duration": integerDuration,
                                "sampleRate": currentSettings[AVSampleRateKey] as? Int ?? 44100, // Use original or default
                                "channels": currentSettings[AVNumberOfChannelsKey] as? Int ?? 1, // Use original or default
                                "bitrate": currentSettings[AVEncoderBitRateKey] as? Int ?? 128000, // Use original or default
                                "createdAt": Int(Date().timeIntervalSince1970 * 1000), // Current timestamp in milliseconds
                                "filename": outputURL.lastPathComponent,
                                "base64": base64Audio ?? "",
                            ]

                            call.resolve(response)
                        } catch {
                            call.reject("Failed to process trimmed audio: \(error.localizedDescription)")
                        }
                    case .failed:
                        let errorMessage = exportSession.error?.localizedDescription ?? "Unknown playback error"
                        let errorDetails = """
                            Export failed:
                            Error: \(errorMessage)
                            Source URL: \(sourceURL)
                            Source exists: \(FileManager.default.fileExists(atPath: sourceURL.path))
                            Start Time: \(startTime)
                            End Time: \(endTime)
                            Duration: \(asset.duration.seconds)
                            Output URL: \(outputURL)
                        """
                        call.reject(errorDetails)
                    case .cancelled:
                        call.reject("Export cancelled")
                    default:
                        call.reject("Export failed with status: \(exportSession.status.rawValue)")
                    }

                    // Clear the reference after export is complete
                    self.exportSession = nil
                }
            }
        } catch {
            call.reject("Failed to trim audio: \(error.localizedDescription)")
        }
    }
    // Function to merge two audio files with completion handler
    private func mergeAudioFiles(firstFile: URL, secondFile: URL, outputFile: URL, requiredDuration: Double, completion: @escaping (Result<Void, Error>) -> Void) {
        do {
            // Create a composition
            let composition = AVMutableComposition()

            // Create an audio track in the composition
            guard let compositionTrack = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
                throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create composition track"])
            }

            // Load the first audio file
            let firstAsset = AVAsset(url: firstFile)
            guard let firstTrack = firstAsset.tracks(withMediaType: .audio).first else {
                throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "First file has no audio track"])
            }

            // Load the second audio file
            let secondAsset = AVAsset(url: secondFile)
            guard let secondTrack = secondAsset.tracks(withMediaType: .audio).first else {
                throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Second file has no audio track"])
            }

            // Check for format compatibility
            guard let firstFormatAny = firstTrack.formatDescriptions.first,
                  let secondFormatAny = secondTrack.formatDescriptions.first else {
                throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to get format descriptions"])
            }

            let firstFormat = firstFormatAny as! CMFormatDescription
            let secondFormat = secondFormatAny as! CMFormatDescription

            let firstASBD = CMAudioFormatDescriptionGetStreamBasicDescription(firstFormat)
            let secondASBD = CMAudioFormatDescriptionGetStreamBasicDescription(secondFormat)

            if let firstASBD = firstASBD, let secondASBD = secondASBD {
                if firstASBD.pointee.mSampleRate != secondASBD.pointee.mSampleRate {
                    log("Warning: Sample rate mismatch between segments: \(firstASBD.pointee.mSampleRate) vs \(secondASBD.pointee.mSampleRate)")
                    // We'll continue anyway, but this could cause quality issues
                }
            }

            // Calculate the start time in the first file (we want to take only the required duration from the end)
            let firstFileDuration = firstAsset.duration.seconds

            // Ensure we don't request more than what's available
            let validRequiredDuration = min(requiredDuration, firstFileDuration)

            let startTime = max(0, firstFileDuration - validRequiredDuration)

            // Use a higher precision timescale for better accuracy
            let highPrecisionTimescale: CMTimeScale = 48000
            let startCMTime = CMTime(seconds: startTime, preferredTimescale: highPrecisionTimescale)

            // Calculate the actual duration of the first portion we'll use
            let firstPortionDuration = min(validRequiredDuration, firstFileDuration - startTime)
            let firstPortionCMTime = CMTime(seconds: firstPortionDuration, preferredTimescale: highPrecisionTimescale)

            log("Merging audio files:")
            log("First file duration: \(firstFileDuration) seconds")
            log("Required duration: \(requiredDuration) seconds")
            log("Valid required duration: \(validRequiredDuration) seconds")
            log("Start time in first file: \(startTime) seconds")
            log("First portion duration: \(firstPortionDuration) seconds")
            log("Second file duration: \(secondAsset.duration.seconds) seconds")

            // Insert only the required portion of the first audio track into the composition
            try compositionTrack.insertTimeRange(
                CMTimeRange(start: startCMTime, duration: firstPortionCMTime),
                of: firstTrack,
                at: .zero
            )

            // Get the exact end time of the first segment in the composition
            let firstSegmentEndTime = CMTimeAdd(
                .zero,  // We started at zero in the composition
                firstPortionCMTime
            )

            // Use the exact end time of the first segment as the start time for the second segment
            // This ensures perfect continuity with no gap
            let secondSegmentStartTime = firstSegmentEndTime

            log("First segment end time: \(CMTimeGetSeconds(firstSegmentEndTime)) seconds")
            log("Second segment start time: \(CMTimeGetSeconds(secondSegmentStartTime)) seconds")

            // Insert the second audio track into the composition at the exact end of the first segment
            // This ensures there's no gap between segments
            try compositionTrack.insertTimeRange(
                CMTimeRange(start: .zero, duration: secondAsset.duration),
                of: secondTrack,
                at: secondSegmentStartTime
            )

            // Apply audio mix to ensure smooth transition between segments
            let audioMix = AVMutableAudioMix()

            // Create parameters for the composition track
            let audioMixInputParameters = AVMutableAudioMixInputParameters(track: compositionTrack)
            // Set volume ramp for the transition point to ensure smooth crossfade
            let crossfadeDuration = AudioEngineConstants.crossfadeDuration // 20 milliseconds crossfade

            // Calculate the crossfade start and end times
            // Since secondSegmentStartTime equals firstSegmentEndTime, we need to create a crossfade
            // that spans equally before and after the transition point
            let crossfadeStartTime = CMTimeSubtract(firstSegmentEndTime, CMTime(seconds: crossfadeDuration/2, preferredTimescale: highPrecisionTimescale))
            let crossfadeEndTime = CMTimeAdd(secondSegmentStartTime, CMTime(seconds: crossfadeDuration/2, preferredTimescale: highPrecisionTimescale))

            // Set volume ramps for smooth transition
            audioMixInputParameters.setVolumeRamp(
                fromStartVolume: 1.0,
                toEndVolume: 1.0,
                timeRange: CMTimeRange(start: .zero, end: crossfadeStartTime)
            )

            // Create a smooth crossfade at the transition point
            audioMixInputParameters.setVolumeRamp(
                fromStartVolume: 1.0,
                toEndVolume: 0.0,
                timeRange: CMTimeRange(start: crossfadeStartTime, end: firstSegmentEndTime)
            )

            audioMixInputParameters.setVolumeRamp(
                fromStartVolume: 0.0,
                toEndVolume: 1.0,
                timeRange: CMTimeRange(start: secondSegmentStartTime, end: crossfadeEndTime)
            )

            audioMix.inputParameters = [audioMixInputParameters]

            // Create an export session
            guard let exportSession = AVAssetExportSession(asset: composition, presetName: AVAssetExportPresetAppleM4A) else {
                throw NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to create export session"])
            }

            // Configure the export session
            exportSession.outputURL = outputFile
            exportSession.outputFileType = .m4a
            exportSession.audioMix = audioMix

            // Remove the output file if it already exists
            if FileManager.default.fileExists(atPath: outputFile.path) {
                try FileManager.default.removeItem(at: outputFile)
            }

            // Use a high QoS queue for the export completion handler
            let userInitiatedQueue = DispatchQueue.global(qos: .userInitiated)

            // Export the composition asynchronously with completion handler
            exportSession.exportAsynchronously {
                userInitiatedQueue.async {
                    if exportSession.status == .completed {
                        self.log("Successfully merged audio files to: \(outputFile.path)")
                        completion(.success(()))
                    } else if let error = exportSession.error {
                        completion(.failure(error))
                    } else {
                        let error = NSError(domain: "AudioEngine", code: -1, userInfo: [NSLocalizedDescriptionKey: "Export failed with unknown error"])
                        completion(.failure(error))
                    }
                }
            }
        } catch {
            completion(.failure(error))
        }
    }

    // MARK: - AVAudioPlayerDelegate Methods

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        isPlaying = false
        stopPlaybackProgressTimer()

        if flag {
            // Notify listeners of completion
            let eventData: [String: Any] = [
                "duration": player.duration
            ]
            notifyListeners("playbackCompleted", data: eventData)

            // If not looping, update status
            if !isLooping {
                let statusData: [String: Any] = [
                    "status": "completed",
                    "currentTime": player.duration,
                    "duration": player.duration
                ]
                notifyListeners("playbackStatusChange", data: statusData)
            }
        }
    }

    public func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        isPlaying = false
        stopPlaybackProgressTimer()

        // Notify listeners of error
        let eventData: [String: Any] = [
            "message": error?.localizedDescription ?? "Unknown playback error",
            "code": "DECODE_ERROR"
        ]
        notifyListeners("playbackError", data: eventData)
    }

    // MARK: - Preloaded Audio Management

    @objc func clearPreloadedAudio(_ call: CAPPluginCall) {
        let uri = call.getString("uri")

        if let uri = uri {
            // Clear specific preloaded audio
            if let player = preloadedAudioPlayers[uri] {
                player.stop()
                preloadedAudioPlayers.removeValue(forKey: uri)
                preloadedAudioData.removeValue(forKey: uri)
                call.resolve(["success": true, "message": "Cleared preloaded audio for: \(uri)"])
            } else {
                call.resolve(["success": false, "message": "No preloaded audio found for: \(uri)"])
            }
        } else {
            // Clear all preloaded audio
            let count = preloadedAudioPlayers.count
            for (_, player) in preloadedAudioPlayers {
                player.stop()
            }
            preloadedAudioPlayers.removeAll()
            preloadedAudioData.removeAll()
            call.resolve(["success": true, "message": "Cleared \(count) preloaded audio files"])
        }
    }

    @objc func getPreloadedAudio(_ call: CAPPluginCall) {
        let preloadedUris = Array(preloadedAudioPlayers.keys)
        let sizes = preloadedAudioData.mapValues { $0.count }

        call.resolve([
            "success": true,
            "preloadedAudio": preloadedUris,
            "sizes": sizes,
            "count": preloadedUris.count
        ])
    }

    @objc func isAudioPreloaded(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        let isPreloaded = preloadedAudioPlayers[uri] != nil
        call.resolve([
            "success": true,
            "preloaded": isPreloaded,
            "uri": uri
        ])
    }

    // Cleanup when plugin is destroyed
    deinit {
        log("Plugin is being destroyed, cleaning up resources")

        // Stop duration monitoring
        stopDurationMonitoring()

        // Clean up segment timer properly
        stopSegmentTimer()

        // Stop recording if active
        if let recorder = audioRecorder {
            if recorder.isRecording {
                log("Stopping active recording during cleanup")
                recorder.stop()
            }
            audioRecorder = nil
        }
        isRecording = false

        // Cancel export if in progress
        if let session = exportSession {
            log("Canceling active export session during cleanup")
            session.cancelExport()
        }
        exportSession = nil

        // Deactivate audio session
        try? recordingSession?.setActive(false)
        recordingSession = nil

        // Stop monitoring for interruptions
        stopInterruptionMonitoring()

        // Clean up segment files
        log("Cleaning up \(segmentFiles.count) segment files")
        for segmentPath in segmentFiles {
            try? FileManager.default.removeItem(at: segmentPath)
            log("Removed segment file: \(segmentPath.lastPathComponent)")
        }
        segmentFiles.removeAll()

        // Clean up playback resources
        audioPlayer?.stop()
        audioPlayer = nil
        urlSessionTask?.cancel()
        urlSessionTask = nil
        stopPlaybackProgressTimer()

        // Clean up preloaded audio resources
        log("Cleaning up \(preloadedAudioPlayers.count) preloaded audio players")
        for (uri, player) in preloadedAudioPlayers {
            player.stop()
            log("Stopped preloaded audio: \(uri)")
        }
        preloadedAudioPlayers.removeAll()
        preloadedAudioData.removeAll()

        log("Plugin cleanup completed")
    }

    @objc public override func addListener(_ call: CAPPluginCall) {
        guard let eventName = call.getString("eventName") else {
            call.reject("Event name is required")
            return
        }

        // Validate event name - include both recording and playback events
        switch eventName {
        case "recordingInterruption", "durationChange", "error":
            log("Adding recording listener for \(eventName)")
            super.addListener(call)

            if eventName == "durationChange" {
                log("Duration change listener added")

                // If we're recording, immediately restart duration monitoring to ensure events fire
                if isRecording {
                    log("Restarting duration monitoring because listener was added while recording")
                    stopDurationMonitoring() // Ensure clean state
                    startDurationMonitoring()

                    // Also emit an immediate event with the current duration
                    let intDuration = Int(currentDuration)
                    log("Emitting immediate durationChange event with current duration: \(intDuration)")
                    notifyListeners("durationChange", data: [
                        "duration": intDuration
                    ])
                }
            }

            call.resolve()
        case "playbackProgress", "playbackStatusChange", "playbackCompleted", "playbackError":
            log("Adding playback listener for \(eventName)")
            super.addListener(call)

            // If we're currently playing and this is a progress listener, make sure the timer is running
            if eventName == "playbackProgress" && isPlaying && audioPlayer != nil {
                log("Restarting playback progress timer because listener was added while playing")
                startPlaybackProgressTimer()

                // Also emit an immediate progress event with current state
                if let player = audioPlayer {
                    let currentTime = player.currentTime
                    let duration = player.duration
                    let position = duration > 0 ? (currentTime / duration) * 100 : 0

                    log("Emitting immediate playbackProgress event")
                    notifyListeners("playbackProgress", data: [
                        "currentTime": currentTime,
                        "duration": duration,
                        "position": position
                    ])
                }
            }

            call.resolve()
        default:
            call.reject("Unknown event name: \(eventName)")
        }
    }

    @objc public override func removeAllListeners(_ call: CAPPluginCall) {
        log("Removing all listeners")
        super.removeAllListeners(call)
        call.resolve()
    }

    @objc public func removeAllListeners() {
        log("Removing all listeners (no-arg version)")
        // Direct access to eventListeners property is the cleanest approach
        self.eventListeners?.removeAllObjects()
    }

    public func handleInterruptionBegan() {
        if isRecording {
            wasRecordingBeforeInterruption = true
            do {
                // Stop duration monitoring when interruption begins
                stopDurationMonitoring()

                audioRecorder?.pause()
                try recordingSession?.setActive(false, options: .notifyOthersOnDeactivation)
            } catch {
                log("Failed to handle interruption: \(error.localizedDescription)")
            }
        }
    }

    public func handleInterruptionEnded(shouldResume: Bool) {
        if wasRecordingBeforeInterruption && shouldResume {
            do {
                // Reactivate audio session
                try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

                // Resume recording if possible
                if let recorder = audioRecorder, isRecording {
                    if recorder.record() {
                        log("Successfully resumed recording after interruption")

                        // Restart segment timer if maxDuration is set
                        if let maxDuration = maxDuration, maxDuration > 0 {
                            log("Restarting segment timer after interruption")
                            startSegmentTimer(maxDuration: maxDuration)
                        }

                        // Restart duration monitoring after interruption
                        startDurationMonitoring()

                        notifyListeners("recordingInterruption", data: [
                            "message": "Recording resumed after interruption"
                        ])
                    } else {
                        log("Failed to resume recording after interruption")
                        wasRecordingBeforeInterruption = false

                        notifyListeners("recordingInterruption", data: [
                            "message": "Failed to resume recording after interruption"
                        ])
                    }
                }
            } catch {
                log("Failed to resume recording after app state change: \(error.localizedDescription)")
                wasRecordingBeforeInterruption = false

                notifyListeners("recordingInterruption", data: [
                    "message": "Failed to resume recording after interruption: \(error.localizedDescription)"
                ])
            }
            wasRecordingBeforeInterruption = false
        }
    }

    @objc func switchMicrophone(_ call: CAPPluginCall) {
        guard let microphoneId = call.getInt("microphoneId") else {
            call.reject("Microphone ID is required")
            return
        }

        let audioSession = AVAudioSession.sharedInstance()

        guard let availableInputs = audioSession.availableInputs,
              microphoneId < availableInputs.count else {
            call.reject("Invalid microphone ID")
            return
        }

        let targetInput = availableInputs[microphoneId]

        do {
            // Check if recording is currently active
            let wasRecording = audioRecorder?.isRecording ?? false

            // Stop current recording
            if wasRecording {
                audioRecorder?.stop()
                stopDurationMonitoring()
            }

            // Switch to new input
            try audioSession.setPreferredInput(targetInput)
            try audioSession.setActive(true)

            // Recreate audio recorder if it was recording
            if wasRecording {
                setupAudioRecorder()
                audioRecorder?.record()
                startDurationMonitoring()
            }

            call.resolve([
                "success": true,
                "microphoneId": microphoneId
            ])
        } catch {
            call.reject("Failed to switch microphone: \(error.localizedDescription)")
        }
    }

    @objc func isMicrophoneBusy(_ call: CAPPluginCall) {
        var isBusy = false
        var reason = "Microphone is available"

        let audioSession = AVAudioSession.sharedInstance()

        // Method 1: Check if we're already recording
        if audioRecorder?.isRecording == true {
            isBusy = true
            reason = "Currently recording with this app"
            call.resolve(["busy": isBusy, "reason": reason])
            return
        }

        // Method 2: Use system APIs to check microphone availability
        let systemCheck = checkMicrophoneAvailabilityWithSystemAPIs()
        if systemCheck.isBusy {
            call.resolve(["busy": systemCheck.isBusy, "reason": systemCheck.reason])
            return
        }

        // Method 3: Check if other audio is playing (indicates another app might be using audio)
        if audioSession.isOtherAudioPlaying {
            isBusy = true
            reason = "Another app is playing audio"
            call.resolve(["busy": isBusy, "reason": reason])
            return
        }

        // Method 4: Use AVAudioEngine to test microphone access without recording
        let audioEngine = AVAudioEngine()
        let inputNode = audioEngine.inputNode

        // Store original audio session settings
        let originalCategory = audioSession.category
        let originalMode = audioSession.mode
        let originalOptions = audioSession.categoryOptions

        do {
            // Set recording category to test microphone access
            try audioSession.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
            try audioSession.setActive(true)

            // Try to get the input format - this will fail if mic is busy
            let format = inputNode.outputFormat(forBus: 0)

            // Verify we have a valid format
            if format.sampleRate == 0 || format.channelCount == 0 {
                isBusy = true
                reason = "Microphone format unavailable - may be in use"
            } else {
                // Try to install a tap on the input node
                // This is a lightweight way to test mic access without actually recording
                inputNode.installTap(onBus: 0, bufferSize: AudioEngineConstants.bufferSize, format: format) { (buffer, time) in
                    // We don't process the buffer, just test that we can access it
                }

                // Try to prepare the engine (but don't start it)
                audioEngine.prepare()

                // If we get here, the microphone should be available
                isBusy = false
                reason = "Microphone is available"

                // Clean up immediately
                inputNode.removeTap(onBus: 0)
            }

        } catch let error {
            // Analyze the error to determine if microphone is busy
            if let nsError = error as NSError? {
                switch nsError.code {
                case 1718449215: // 'what' - hardware not available (busy)
                    isBusy = true
                    reason = "Microphone hardware is busy with another app"
                case 560030580: // '!dev' - device not available
                    isBusy = true
                    reason = "Microphone device is not available"
                case 561145203: // '!pla' - session interruption
                    isBusy = true
                    reason = "Audio session interrupted by another app"
                case 1936290409: // 'auth' - authorization error
                    isBusy = true
                    reason = "Microphone access denied - check permissions"
                case 2003329396: // 'fmt?' - format not supported
                    isBusy = true
                    reason = "Microphone format not available - may be in use"
                case -50: // Invalid parameter
                    isBusy = false
                    reason = "Microphone is available"
                default:
                    // Most errors indicate the microphone is not available
                    isBusy = true
                    reason = "Microphone may be busy: \(error.localizedDescription) (Code: \(nsError.code))"
                }
            } else {
                isBusy = true
                reason = "Microphone access error: \(error.localizedDescription)"
            }
        }

        // Always restore original audio session settings
        do {
            try audioSession.setCategory(originalCategory, mode: originalMode, options: originalOptions)
            if audioSession.isOtherAudioPlaying {
                // Don't deactivate if other audio is playing
                try audioSession.setActive(false, options: .notifyOthersOnDeactivation)
            } else {
                try audioSession.setActive(false)
            }
        } catch {
            log("Failed to restore audio session: \(error)")
        }

        call.resolve(["busy": isBusy, "reason": reason])
    }

    @objc func getAvailableMicrophones(_ call: CAPPluginCall) {
        let audioSession = AVAudioSession.sharedInstance()
        var microphones: [[String: Any]] = []

        guard let availableInputs = audioSession.availableInputs else {
            call.resolve([
                "microphones": microphones
            ])
            return
        }

        for (index, input) in availableInputs.enumerated() {
            var micType = "Unknown"
            var description = input.portName

            switch input.portType {
            case .builtInMic:
                micType = "internal"
                description = "Built-in Microphone"
            case .headsetMic:
                micType = "external"
                description = "Headset Microphone"
            case .bluetoothHFP:
                micType = "external"
                description = "Bluetooth Microphone"
            case .usbAudio:
                micType = "external"
                description = "USB Microphone"
            case .airPlay:
                micType = "external"
                description = "AirPlay Audio"
            default:
                micType = "unknown"
                description = input.portName
            }

            let microphone: [String: Any] = [
                "id": index,
                "name": input.portName,
                "type": micType,
                "description": description,
                "uid": input.uid
            ]

            microphones.append(microphone)
        }

        call.resolve([
            "microphones": microphones
        ])
    }

    private func setupAudioRecorder() {
        guard let recordingPath = recordingPath else { return }

        let settings = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: Int(AudioEngineConstants.defaultSampleRate),
            AVNumberOfChannelsKey: AudioEngineConstants.defaultChannels,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]

        do {
            audioRecorder = try AVAudioRecorder(url: recordingPath, settings: settings)
            audioRecorder?.prepareToRecord()
        } catch {
            log("Failed to setup audio recorder: \(error)")
        }
    }

    // MARK: - Audio Playback Methods

    @objc func preload(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        let prepare = call.getBool("prepare") ?? true

        // Check if already preloaded
        if let preloadedPlayer = preloadedAudioPlayers[uri] {
            // Return audio info for already preloaded audio
            Task {
                do {
                    let audioInfo = try await self.extractAudioInfo(from: uri)
                    call.resolve(audioInfo)
                } catch {
                    // Fallback to basic info if extraction fails
                    call.resolve([
                        "uri": uri,
                        "duration": preloadedPlayer.duration,
                        "mimeType": "audio/m4a",
                        "createdAt": Int64(Date().timeIntervalSince1970 * 1000)
                    ])
                }
            }
            return
        }

        // Check if it's a remote
        if isRemoteURL(uri) {
            guard checkNetworkAvailability() else {
                call.reject("Network is not available for remote audio URL")
                return
            }
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // Setup audio session for playback
                let audioSession = AVAudioSession.sharedInstance()
                try audioSession.setCategory(.playback, mode: .default)
                try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

                // Cancel any ongoing network tasks (but don't stop current playback)
                self.urlSessionTask?.cancel()
                self.urlSessionTask = nil

                // Check if it's a remote URL
                if self.isRemoteURL(uri) {
                    self.preloadRemoteAudio(uri: uri, prepare: prepare, call: call)
                } else {
                    self.preloadLocalAudio(uri: uri, prepare: prepare, call: call)
                }

            } catch {
                DispatchQueue.main.async {
                    call.reject("Failed to setup audio session for preload: \(error.localizedDescription)")
                }
            }
        }
    }

    private func preloadRemoteAudio(uri: String, prepare: Bool, call: CAPPluginCall) {
        // Check network connectivity first
        guard checkNetworkAvailability() else {
            DispatchQueue.main.async {
                call.reject("Network is not available for remote audio URL")
            }
            return
        }

        guard let request = createOptimizedURLRequest(from: uri) else {
            DispatchQueue.main.async {
                call.reject("Invalid remote URL")
            }
            return
        }

        log("Preloading remote audio from: \(uri)")

        // Create URLSessionConfiguration for better CDN handling
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = AudioEngineConstants.networkTimeout
        config.timeoutIntervalForResource = AudioEngineConstants.resourceTimeout
        config.waitsForConnectivity = true

        let session = URLSession(configuration: config)

        // Download audio for preloading with enhanced error handling
        self.urlSessionTask = session.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }

                // Handle network errors with specific messages
                if let error = error {
                    let nsError = error as NSError
                    var errorMessage = "Failed to preload audio"

                    switch nsError.code {
                    case NSURLErrorNotConnectedToInternet:
                        errorMessage = "No internet connection available"
                    case NSURLErrorTimedOut:
                        errorMessage = "Request timed out - CDN may be slow or unreachable"
                    case NSURLErrorCannotFindHost:
                        errorMessage = "Cannot find CDN host"
                    case NSURLErrorCannotConnectToHost:
                        errorMessage = "Cannot connect to CDN host"
                    case NSURLErrorNetworkConnectionLost:
                        errorMessage = "Network connection lost during download"
                    case NSURLErrorResourceUnavailable:
                        errorMessage = "Audio resource is unavailable on CDN"
                    case NSURLErrorBadURL:
                        errorMessage = "Invalid CDN URL format"
                    default:
                        errorMessage = "Network error: \(error.localizedDescription)"
                    }

                    call.reject(errorMessage)
                    return
                }

                guard let data = data else {
                    call.reject("No audio data received from CDN")
                    return
                }

                // Check HTTP response with detailed error messages
                if let httpResponse = response as? HTTPURLResponse {
                    self.log("HTTP Response: \(httpResponse.statusCode) for URL: \(uri)")

                    switch httpResponse.statusCode {
                    case 200...299:
                        break // Success
                    case 300...399:
                        call.reject("CDN returned redirect (HTTP \(httpResponse.statusCode)) - check URL")
                        return
                    case 400...499:
                        call.reject("CDN client error (HTTP \(httpResponse.statusCode)) - audio file not found or access denied")
                        return
                    case 500...599:
                        call.reject("CDN server error (HTTP \(httpResponse.statusCode)) - try again later")
                        return
                    default:
                        call.reject("CDN returned unexpected status code: \(httpResponse.statusCode)")
                        return
                    }

                    // Validate content type
                    if let contentType = httpResponse.allHeaderFields["Content-Type"] as? String {
                        self.log("Content-Type: \(contentType)")
                        if !contentType.contains("audio") && !contentType.contains("application/octet-stream") {
                            self.log("Warning: Unexpected content type \(contentType) for audio file")
                        }
                    }
                }

                self.log("Successfully downloaded \(data.count) bytes for preload")

                // Create audio player from downloaded data with enhanced error handling
                do {
                    let preloadedPlayer = try AVAudioPlayer(data: data)
                    preloadedPlayer.delegate = self

                    // Store in preloaded dictionary instead of overwriting current player
                    self.preloadedAudioPlayers[uri] = preloadedPlayer
                    self.preloadedAudioData[uri] = data

                    if prepare {
                        let prepared = preloadedPlayer.prepareToPlay()
                        if prepared {
                            self.log("Successfully prepared remote audio for playback")

                            // Extract and return audio info
                            Task {
                                do {
                                    var audioInfo = try await self.extractAudioInfo(from: uri)
                                    // Update with actual data size for remote files
                                    audioInfo["size"] = data.count
                                    call.resolve(audioInfo)
                                } catch {
                                    // Fallback to basic info if extraction fails
                                    call.resolve([
                                        "uri": uri,
                                        "duration": preloadedPlayer.duration,
                                        "size": data.count,
                                        "mimeType": "audio/m4a",
                                        "path": uri,
                                        "webPath": uri,
                                        "sampleRate": AudioEngineConstants.defaultSampleRate,
                                        "channels": AudioEngineConstants.defaultChannels,
                                        "bitrate": AudioEngineConstants.defaultBitrate,
                                        "createdAt": Int64(Date().timeIntervalSince1970 * 1000),
                                        "filename": self.extractFilenameFromURI(uri)
                                    ])
                                }
                            }
                        } else {
                            call.reject("Failed to prepare remote audio for playback - audio format may be unsupported")
                        }
                    } else {
                        // Extract and return audio info without preparing
                        Task {
                            do {
                                var audioInfo = try await self.extractAudioInfo(from: uri)
                                // Update with actual data size for remote files
                                audioInfo["size"] = data.count
                                // Duration might be 0 if not prepared
                                if (audioInfo["duration"] as? Double ?? 0) == 0 {
                                    audioInfo["duration"] = 0.0
                                }
                                call.resolve(audioInfo)
                            } catch {
                                // Fallback to basic info if extraction fails
                                call.resolve([
                                    "uri": uri,
                                    "duration": 0.0,
                                    "size": data.count,
                                    "mimeType": "audio/m4a",
                                    "path": uri,
                                    "webPath": uri,
                                    "sampleRate": AudioEngineConstants.defaultSampleRate,
                                    "channels": AudioEngineConstants.defaultChannels,
                                    "bitrate": AudioEngineConstants.defaultBitrate,
                                    "createdAt": Int64(Date().timeIntervalSince1970 * 1000),
                                    "filename": self.extractFilenameFromURI(uri)
                                ])
                            }
                        }
                    }

                } catch {
                    let avError = error as? AVError
                    var errorMessage = "Failed to create audio player from CDN data"

                    if let avError = avError {
                        switch avError.code {
                        case .fileFormatNotRecognized:
                            errorMessage = "Audio format not supported - CDN file may be corrupted"
                        case .fileFailedToParse:
                            errorMessage = "Failed to parse audio file from CDN"
                        case .unknown:
                            errorMessage = "Invalid audio source from CDN"
                        default:
                            errorMessage = "Audio error: \(avError.localizedDescription)"
                        }
                    }

                    call.reject(errorMessage)
                }
            }
        }

        self.urlSessionTask?.resume()
    }

    private func preloadLocalAudio(uri: String, prepare: Bool, call: CAPPluginCall) {
        let url = URL(string: uri) ?? URL(fileURLWithPath: uri)

        do {
            let preloadedPlayer = try AVAudioPlayer(contentsOf: url)
            preloadedPlayer.delegate = self

            // Store in preloaded dictionary instead of overwriting current player
            self.preloadedAudioPlayers[uri] = preloadedPlayer

            if prepare {
                let prepared = preloadedPlayer.prepareToPlay()
                DispatchQueue.main.async {
                    if prepared {
                        // Extract and return audio info
                        Task {
                            do {
                                let audioInfo = try await self.extractAudioInfo(from: uri)
                                call.resolve(audioInfo)
                            } catch {
                                // Fallback to basic info if extraction fails
                                call.resolve([
                                    "uri": uri,
                                    "duration": preloadedPlayer.duration,
                                    "mimeType": "audio/m4a",
                                    "path": uri,
                                    "webPath": uri,
                                    "sampleRate": AudioEngineConstants.defaultSampleRate,
                                    "channels": AudioEngineConstants.defaultChannels,
                                    "bitrate": AudioEngineConstants.defaultBitrate,
                                    "createdAt": Int64(Date().timeIntervalSince1970 * 1000),
                                    "filename": self.extractFilenameFromURI(uri)
                                ])
                            }
                        }
                    } else {
                        call.reject("Failed to prepare local audio for playback")
                    }
                }
            } else {
                DispatchQueue.main.async {
                    // Extract and return audio info without preparing
                    Task {
                        do {
                            var audioInfo = try await self.extractAudioInfo(from: uri)
                            // Duration might be 0 if not prepared
                            if (audioInfo["duration"] as? Double ?? 0) == 0 {
                                audioInfo["duration"] = 0.0
                            }
                            call.resolve(audioInfo)
                        } catch {
                            // Fallback to basic info if extraction fails
                            call.resolve([
                                "uri": uri,
                                "duration": 0.0,
                                "mimeType": "audio/m4a",
                                "path": uri,
                                "webPath": uri,
                                "sampleRate": AudioEngineConstants.defaultSampleRate,
                                "channels": AudioEngineConstants.defaultChannels,
                                "bitrate": AudioEngineConstants.defaultBitrate,
                                "createdAt": Int64(Date().timeIntervalSince1970 * 1000),
                                "filename": self.extractFilenameFromURI(uri)
                            ])
                        }
                    }
                }
            }

        } catch {
            DispatchQueue.main.async {
                call.reject("Failed to preload audio: \(error.localizedDescription)")
            }
        }
    }

    @objc func startPlayback(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        // Get playback options
        let speed = call.getFloat("speed") ?? 1.0
        let volume = call.getFloat("volume") ?? 1.0
        let loop = call.getBool("loop") ?? false
        let startTime = call.getDouble("startTime") ?? 0.0

        // Check if this URI is already preloaded (PERFORMANCE ENHANCEMENT)
        if let preloadedPlayer = preloadedAudioPlayers[uri] {
            log("Using preloaded audio for: \(uri)")

            DispatchQueue.main.async {
                // Stop any current playback
                self.audioPlayer?.stop()

                // Use the preloaded player
                self.audioPlayer = preloadedPlayer
                self.currentPlaybackPath = uri
                self.playbackSpeed = speed
                self.playbackVolume = volume
                self.isLooping = loop

                // Configure playback settings
                preloadedPlayer.currentTime = startTime
                preloadedPlayer.rate = speed
                preloadedPlayer.volume = volume
                preloadedPlayer.numberOfLoops = loop ? -1 : 0

                // Start playback
                if preloadedPlayer.play() {
                    self.isPlaying = true
                    self.startPlaybackProgressTimer()
                    call.resolve([
                        "success": true,
                        "preloaded": true,
                        "message": "Started playback from preloaded audio"
                    ])
                } else {
                    call.reject("Failed to start playback of preloaded audio")
                }
            }
            return
        }

        // If not preloaded, check network connectivity for remote URLs
        if isRemoteURL(uri) {
            guard checkNetworkAvailability() else {
                call.reject("Network is not available for remote audio URL")
                return
            }
        }

        DispatchQueue.global(qos: .userInitiated).async {
            do {
                // Setup audio session for playback
                let audioSession = AVAudioSession.sharedInstance()
                try audioSession.setCategory(.playback, mode: .default)
                try audioSession.setActive(true, options: .notifyOthersOnDeactivation)

                // Stop any current playback and network tasks
                self.audioPlayer?.stop()
                self.audioPlayer = nil
                self.urlSessionTask?.cancel()
                self.urlSessionTask = nil

                self.playbackSpeed = speed
                self.playbackVolume = volume
                self.isLooping = loop

                // Load and play audio (since it's not preloaded)
                self.log("Loading audio on-demand for: \(uri)")
                if self.isRemoteURL(uri) {
                    self.playRemoteAudio(uri: uri, speed: speed, volume: volume, loop: loop, startTime: startTime, call: call)
                } else {
                    self.playLocalAudio(uri: uri, speed: speed, volume: volume, loop: loop, startTime: startTime, call: call)
                }

            } catch {
                DispatchQueue.main.async {
                    call.reject("Failed to setup audio session: \(error.localizedDescription)")
                }
            }
        }
    }

    private func playRemoteAudio(uri: String, speed: Float, volume: Float, loop: Bool, startTime: Double, call: CAPPluginCall) {
        guard let request = createOptimizedURLRequest(from: uri) else {
            DispatchQueue.main.async {
                call.reject("Invalid remote URL")
            }
            return
        }

        log("Starting playback of remote audio from: \(uri)")

        // Create URLSessionConfiguration for better CDN handling
        let config = URLSessionConfiguration.default
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = 60.0
        config.timeoutIntervalForResource = 120.0
        config.waitsForConnectivity = true

        let session = URLSession(configuration: config)

        // Download and play audio with enhanced error handling
        self.urlSessionTask = session.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }

                // Handle network errors with specific messages
                if let error = error {
                    let nsError = error as NSError
                    var errorMessage = "Failed to play remote audio"

                    switch nsError.code {
                    case NSURLErrorNotConnectedToInternet:
                        errorMessage = "No internet connection available"
                    case NSURLErrorTimedOut:
                        errorMessage = "Request timed out - CDN may be slow or unreachable"
                    case NSURLErrorCannotFindHost:
                        errorMessage = "Cannot find CDN host"
                    case NSURLErrorCannotConnectToHost:
                        errorMessage = "Cannot connect to CDN host"
                    case NSURLErrorNetworkConnectionLost:
                        errorMessage = "Network connection lost during download"
                    case NSURLErrorResourceUnavailable:
                        errorMessage = "Audio resource is unavailable on CDN"
                    case NSURLErrorBadURL:
                        errorMessage = "Invalid CDN URL format"
                    default:
                        errorMessage = "Network error: \(error.localizedDescription)"
                    }

                    call.reject(errorMessage)
                    return
                }

                guard let data = data else {
                    call.reject("No audio data received from CDN")
                    return
                }

                // Check HTTP response with detailed error messages
                if let httpResponse = response as? HTTPURLResponse {
                    self.log("HTTP Response: \(httpResponse.statusCode) for URL: \(uri)")

                    switch httpResponse.statusCode {
                    case 200...299:
                        break // Success
                    case 300...399:
                        call.reject("CDN returned redirect (HTTP \(httpResponse.statusCode)) - check URL")
                        return
                    case 400...499:
                        call.reject("CDN client error (HTTP \(httpResponse.statusCode)) - audio file not found or access denied")
                        return
                    case 500...599:
                        call.reject("CDN server error (HTTP \(httpResponse.statusCode)) - try again later")
                        return
                    default:
                        call.reject("CDN returned unexpected status code: \(httpResponse.statusCode)")
                        return
                    }
                }

                self.log("Successfully downloaded \(data.count) bytes for playback")

                // Create audio player from downloaded data with enhanced error handling
                do {
                    self.audioPlayer = try AVAudioPlayer(data: data)
                    self.audioPlayer?.delegate = self

                    // Configure playback settings
                    self.audioPlayer?.volume = volume
                    self.audioPlayer?.numberOfLoops = loop ? -1 : 0

                    // Enable rate control if available (iOS 11+)
                    if #available(iOS 11.0, *) {
                        self.audioPlayer?.enableRate = true
                        self.audioPlayer?.rate = speed
                    }

                    let prepared = self.audioPlayer?.prepareToPlay() ?? false
                    if !prepared {
                        call.reject("Failed to prepare remote audio for playback - audio format may be unsupported")
                        return
                    }

                    if startTime > 0 {
                        self.audioPlayer?.currentTime = startTime
                    }

                    let started = self.audioPlayer?.play() ?? false
                    if started {
                        self.isPlaying = true
                        self.currentPlaybackPath = uri
                        self.startPlaybackProgressTimer()

                        self.log("Successfully started remote audio playback")

                        // Notify listeners
                        let eventData: [String: Any] = [
                            "status": "playing",
                            "currentTime": self.audioPlayer?.currentTime ??  0,
                            "duration": self.audioPlayer?.duration ?? 0
                        ]
                        self.notifyListeners("playbackStatusChange", data: eventData)
                        call.resolve()
                    } else {
                        call.reject("Failed to start remote audio playback")
                    }

                } catch {
                    let avError = error as? AVError
                    var errorMessage = "Failed to create audio player from CDN data"

                    if let avError = avError {
                        switch avError.code {
                        case .fileFormatNotRecognized:
                            errorMessage = "Audio format not supported - CDN file may be corrupted"
                        case .fileFailedToParse:
                            errorMessage = "Failed to parse audio file from CDN"
                        case .unknown:
                            errorMessage = "Invalid audio source from CDN"
                        default:
                            errorMessage = "Audio error: \(avError.localizedDescription)"
                        }
                    }

                    call.reject(errorMessage)
                }
            }
        }

        self.urlSessionTask?.resume()
    }

    private func playLocalAudio(uri: String, speed: Float, volume: Float, loop: Bool, startTime: Double, call: CAPPluginCall) {
        let url = URL(string: uri) ?? URL(fileURLWithPath: uri)

        do {
            self.audioPlayer = try AVAudioPlayer(contentsOf: url)
            self.audioPlayer?.delegate = self

            // Configure playback settings
            self.audioPlayer?.volume = volume
            self.audioPlayer?.numberOfLoops = loop ? -1 : 0

            // Enable rate control if available (iOS 11+)
            if #available(iOS 11.0, *) {
                self.audioPlayer?.enableRate = true
                self.audioPlayer?.rate = speed
            }

            let prepared = self.audioPlayer?.prepareToPlay() ?? false
            if !prepared {
                DispatchQueue.main.async {
                    call.reject("Failed to prepare local audio for playback")
                }
                return
            }

            if startTime > 0 {
                self.audioPlayer?.currentTime = startTime
            }

            let started = self.audioPlayer?.play() ?? false
            if started {
                self.isPlaying = true
                self.currentPlaybackPath = uri
                self.startPlaybackProgressTimer()

                DispatchQueue.main.async {
                    // Notify listeners
                    let eventData: [String: Any] = [
                        "status": "playing",
                        "currentTime": self.audioPlayer?.currentTime ?? 0,
                        "duration": self.audioPlayer?.duration ?? 0
                    ]
                    self.notifyListeners("playbackStatusChange", data: eventData)
                    call.resolve()
                }
            } else {
                DispatchQueue.main.async {
                    call.reject("Failed to start local audio playback")
                }
            }

        } catch {
            DispatchQueue.main.async {
                call.reject("Failed to play local audio: \(error.localizedDescription)")
            }
        }
    }

    @objc func pausePlayback(_ call: CAPPluginCall) {
        guard let audioPlayer = audioPlayer, isPlaying else {
            call.reject("No active playback to pause")
            return
        }

        audioPlayer.pause()
        isPlaying = false
        stopPlaybackProgressTimer()

        // Notify listeners
        let eventData: [String: Any] = [
            "status": "paused",
            "currentTime": audioPlayer.currentTime,
            "duration": audioPlayer.duration
        ]
        notifyListeners("playbackStatusChange", data: eventData)
        call.resolve()
    }

    @objc func resumePlayback(_ call: CAPPluginCall) {
        let uri = call.getString("uri")
        let speed = call.getFloat("speed") ?? 1.0
        let volume = call.getFloat("volume") ?? 1.0
        let loop = call.getBool("loop") ?? false

        // If uri is provided, switch to that audio file
        if let uri = uri {
            log("Resuming playback for new URI: \(uri)")

            // Stop current playback if any
            if let currentPlayer = audioPlayer {
                currentPlayer.stop()
                stopPlaybackProgressTimer()
            }

            // Try to use preloaded audio first
            if let preloadedPlayer = preloadedAudioPlayers[uri] {
                log("Using preloaded audio for resume")
                audioPlayer = preloadedPlayer
                currentPlaybackPath = uri
                isPlaying = true

                // Apply options
                audioPlayer?.enableRate = true
                audioPlayer?.rate = speed
                audioPlayer?.volume = volume
                audioPlayer?.numberOfLoops = loop ? -1 : 0

                let started = audioPlayer?.play() ?? false
                if started {
                    startPlaybackProgressTimer()

                    // Notify listeners
                    let eventData: [String: Any] = [
                        "status": "playing",
                        "currentTime": audioPlayer?.currentTime ?? 0,
                        "duration": audioPlayer?.duration ?? 0
                    ]
                    notifyListeners("playbackStatusChange", data: eventData)
                    call.resolve()
                } else {
                    call.reject("Failed to resume preloaded audio")
                }
                return
            }

            // If not preloaded, load and play
            if isRemoteURL(uri) {
                playRemoteAudio(uri: uri, speed: speed, volume: volume, loop: loop, startTime: 0.0, call: call)
            } else {
                playLocalAudio(uri: uri, speed: speed, volume: volume, loop: loop, startTime: 0.0, call: call)
            }
            return
        }

        // No URI provided, resume current playback
        guard let audioPlayer = audioPlayer else {
            call.reject("No active playback to resume")
            return
        }

        if isPlaying {
            call.reject("Playback is already active")
            return
        }

        // Apply options to current player
        audioPlayer.enableRate = true
        audioPlayer.rate = speed
        audioPlayer.volume = volume
        audioPlayer.numberOfLoops = loop ? -1 : 0

        let started = audioPlayer.play()
        if started {
            isPlaying = true
            startPlaybackProgressTimer()

            // Notify listeners
            let eventData: [String: Any] = [
                "status": "playing",
                "currentTime": audioPlayer.currentTime,
                "duration": audioPlayer.duration
            ]
            notifyListeners("playbackStatusChange", data: eventData)
            call.resolve()
        } else {
            call.reject("Failed to resume playback")
        }
    }

    @objc func stopPlayback(_ call: CAPPluginCall) {
        guard let audioPlayer = audioPlayer else {
            call.reject("No active playback to stop")
            return
        }

        audioPlayer.stop()
        isPlaying = false
        stopPlaybackProgressTimer()

        // Cancel any ongoing network downloads
        urlSessionTask?.cancel()
        urlSessionTask = nil

        // Notify listeners
        let eventData: [String: Any] = [
            "status": "stopped",
            "currentTime": 0,
            "duration": audioPlayer.duration
        ]
        notifyListeners("playbackStatusChange", data: eventData)
        call.resolve()
    }

    @objc func seekTo(_ call: CAPPluginCall) {
        guard let time = call.getDouble("time") else {
            call.reject("Time is required")
            return
        }

        guard let audioPlayer = audioPlayer else {
            call.reject("No active playback for seeking")
            return
        }

        audioPlayer.currentTime = time

        // Notify listeners
        let eventData: [String: Any] = [
            "currentTime": time,
            "duration": audioPlayer.duration,
            "position": audioPlayer.duration > 0 ? (time / audioPlayer.duration) * 100 : 0
        ]
        notifyListeners("playbackProgress", data: eventData)
        call.resolve()
    }

    @objc func getPlaybackStatus(_ call: CAPPluginCall) {
        var result: [String: Any] = [:]

        if let audioPlayer = audioPlayer {
            let status = isPlaying ? "playing" : "paused"
            result = [
                "status": status,
                "currentTime": audioPlayer.currentTime,
                "duration": audioPlayer.duration,
                "speed": playbackSpeed,
                "volume": playbackVolume,
                "isLooping": isLooping,
                "uri": currentPlaybackPath ?? ""
            ]
        } else {
            result = [
                "status": "idle",
                "currentTime": 0,
                "duration": 0,
                "speed": 1.0,
                "volume": 1.0,
                "isLooping": false,
                "uri": ""
            ]
        }

        call.resolve(result)
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
        var bitrate: Int = AudioEngineConstants.defaultBitrate

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
            let fileURL = uri.hasPrefix("file://") ? URL(string: uri)! : URL(fileURLWithPath: uri)

            if let attributes = try? FileManager.default.attributesOfItem(atPath: fileURL.path) {
                fileSize = attributes[.size] as? Int64 ?? 0
                if let creationDate = attributes[.creationDate] as? Date {
                    createdAt = creationDate.timeIntervalSince1970
                }
            }

            filename = fileURL.lastPathComponent

            // Determine MIME type based on file extension
            let pathExtension = fileURL.pathExtension.lowercased()
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

    // MARK: - Microphone Availability Detection

    /**
     * Checks if the microphone is currently busy without using test recordings.
     * Uses built-in iOS APIs to detect microphone usage by other apps or system processes.
     *
     * This implementation replaces the previous approach that used test recordings,
     * providing a more efficient and less intrusive way to check microphone availability.
     *
     * Detection methods used:
     * 1. Check if this app is already recording
     * 2. Use system APIs with iOS version-specific checks
     * 3. Check if other audio is playing
     * 4. Use AVAudioEngine to test microphone access without actually recording
     */
    private func checkMicrophoneAvailabilityWithSystemAPIs() -> (isBusy: Bool, reason: String) {
        let audioSession = AVAudioSession.sharedInstance()

        // Check if recording permission is granted
        guard audioSession.recordPermission == .granted else {
            return (true, "Microphone permission not granted")
        }

        // Check for iOS 14+ APIs
        if #available(iOS 14.0, *) {
            // Check if there are any active recording sessions
            // This is a more modern approach using iOS 14+ APIs
            do {
                try audioSession.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
                try audioSession.setActive(true)

                // Check available inputs
                let availableInputs = audioSession.availableInputs ?? []
                let preferredInput = audioSession.preferredInput

                if availableInputs.isEmpty {
                    return (true, "No microphone inputs available")
                }

                // Check if we can set a preferred input (this often fails if mic is busy)
                for input in availableInputs {
                    if input.portType == .builtInMic {
                        do {
                            try audioSession.setPreferredInput(input)
                            // If we can set it, mic is likely available
                            return (false, "Microphone is available")
                        } catch {
                            // If we can't set it, mic might be busy
                            return (true, "Cannot set preferred microphone input - may be in use")
                        }
                    }
                }

                return (false, "Microphone appears available")

            } catch {
                return (true, "Failed to configure audio session for microphone check")
            }
        } else {
            // Fallback for older iOS versions
            return checkMicrophoneAvailabilityLegacy()
        }
    }

    private func checkMicrophoneAvailabilityLegacy() -> (isBusy: Bool, reason: String) {
        let audioSession = AVAudioSession.sharedInstance()

        // Check basic audio session properties
        if audioSession.isOtherAudioPlaying {
            return (true, "Another app is playing audio")
        }

        // Check current route
        let currentRoute = audioSession.currentRoute
        let inputs = currentRoute.inputs

        if inputs.isEmpty {
            return (true, "No audio inputs available")
        }

        // Check if any input is a microphone and is available
        for input in inputs {
            if input.portType == .builtInMic || input.portType == .headsetMic {
                // Check if the input has available data sources
                let dataSources = input.dataSources ?? []
                if dataSources.isEmpty && input.selectedDataSource == nil {
                    // This might indicate the mic is busy
                    return (true, "Microphone input may be in use by another app")
                }
            }
        }

        return (false, "Microphone appears available")
    }

    @objc func destroyAllPlaybacks(_ call: CAPPluginCall) {
        log("Destroying all playback sessions and clearing preloaded audio")

        performStateOperation {
            // Stop current playback if active
            if let player = audioPlayer {
                do {
                    player.stop()
                    isPlaying = false
                    currentPlaybackPath = nil
                    stopPlaybackProgressTimer()
                    audioPlayer = nil
                    log("Current playback stopped and released")
                } catch {
                    log("Error stopping current playback: \(error.localizedDescription)")
                }
            }

            // Cancel any ongoing URL session tasks
            urlSessionTask?.cancel()
            urlSessionTask = nil

            // Clear all preloaded audio
            let preloadedCount = preloadedAudioPlayers.count
            for (uri, player) in preloadedAudioPlayers {
                do {
                    player.stop()
                } catch {
                    log("Error stopping preloaded audio '\(uri)': \(error.localizedDescription)")
                }
            }
            preloadedAudioPlayers.removeAll()
            preloadedAudioData.removeAll()

            // Reset playback state
            playbackSpeed = 1.0
            playbackVolume = 1.0
            isLooping = false

            log("Successfully destroyed all playback sessions. Cleared \(preloadedCount) preloaded audio files")
        }

        call.resolve([
            "success": true,
            "message": "Destroyed all playback sessions and cleared \(preloadedAudioPlayers.count) preloaded audio files"
        ])
    }
}
