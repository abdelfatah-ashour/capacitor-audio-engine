import Foundation
import Capacitor
import AVFoundation
import AVKit
import UIKit
import ObjectiveC
import UserNotifications

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
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
        print("Starting interruption monitoring") // Debug log

        // Audio session interruption notifications
        let interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            print("Received interruption notification") // Debug log
            self?.handleInterruption(notification)
        }
        interruptionObservers.append(interruptionObserver)

        // Audio route change notifications
        let routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            print("Received route change notification") // Debug log
            self?.handleRouteChange(notification)
        }
        interruptionObservers.append(routeChangeObserver)

        // App state change notifications
        let willResignActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App will resign active") // Debug log
            self?.handleAppStateChange(isBackground: true)
        }
        interruptionObservers.append(willResignActiveObserver)

        let didEnterBackgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App did enter background") // Debug log
            self?.handleAppStateChange(isBackground: true)
        }
        interruptionObservers.append(didEnterBackgroundObserver)

        let didBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App did become active") // Debug log
            self?.handleAppStateChange(isBackground: false)
        }
        interruptionObservers.append(didBecomeActiveObserver)
    }

    public func stopInterruptionMonitoring() {
        print("Stopping interruption monitoring") // Debug log
        hasInterruptionListeners = false
        interruptionObservers.forEach { NotificationCenter.default.removeObserver($0) }
        interruptionObservers.removeAll()
    }

    private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            print("Failed to get interruption type") // Debug log
            return
        }

        print("Handling interruption type: \(type)") // Debug log

        switch type {
        case .began:
            handleInterruptionBegan()
            notifyListeners("recordingInterruption", data: [
                "message": "Interruption began"
            ])

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                handleInterruptionEnded(shouldResume: options.contains(.shouldResume))

                if options.contains(.shouldResume) {
                    notifyListeners("recordingInterruption", data: [
                        "message": "Interruption ended - should resume"
                    ])
                } else {
                    notifyListeners("recordingInterruption", data: [
                        "message": "Interruption ended - should not resume"
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

    public func handleAppStateChange(isBackground: Bool) {
        if isBackground {
            if isRecording {
                // For background recording, don't pause - let it continue
                // iOS with background audio mode should allow continuous recording
                print("App entered background - continuing recording with background audio mode")

                // Notify listeners that recording continues in background
                notifyListeners("recordingInterruption", data: [
                    "message": "App entered background - recording continues"
                ])

                // Keep the recording active but ensure proper audio session setup
                do {
                    // Enhanced audio session setup for background recording
                    let session = recordingSession ?? AVAudioSession.sharedInstance()

                    // Use .mixWithOthers to allow background recording alongside other audio
                    try session.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .defaultToSpeaker, .allowBluetooth])
                    try session.setActive(true, options: .notifyOthersOnDeactivation)

                    // Verify recording is still active
                    if let recorder = audioRecorder, !recorder.isRecording {
                        print("Warning: Recorder was paused during background transition, attempting to resume")
                        if !recorder.record() {
                            print("Failed to maintain recording in background")
                            wasRecordingBeforeInterruption = true
                        }
                    }
                } catch {
                    print("Failed to maintain background audio session: \(error.localizedDescription)")
                    // If background setup fails, store state for resume
                    wasRecordingBeforeInterruption = true

                    // Stop timers to save battery
                    stopSegmentTimer()
                    stopDurationMonitoring()

                    audioRecorder?.pause()
                    try? recordingSession?.setActive(false, options: .notifyOthersOnDeactivation)

                    notifyListeners("recordingInterruption", data: [
                        "message": "Background recording failed - will resume when app returns"
                    ])
                }
            }
        } else {
            // App returned to foreground
            print("App returned to foreground")

            if wasRecordingBeforeInterruption {
                do {
                    // Reactivate audio session
                    try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

                    // Resume recording if possible
                    if let recorder = audioRecorder, isRecording {
                        if recorder.record() {
                            print("Successfully resumed recording after app state change")

                            // Restart segment timer if maxDuration is set
                            if let maxDuration = maxDuration, maxDuration > 0 {
                                print("Restarting segment timer after app state change")
                                startSegmentTimer(maxDuration: maxDuration)
                            }

                            // Restart duration monitoring when app comes back to foreground
                            startDurationMonitoring()

                            notifyListeners("recordingInterruption", data: [
                                "message": "Recording resumed after returning to foreground"
                            ])
                        } else {
                            print("Failed to resume recording after app state change")
                        }
                    }
                } catch {
                    print("Failed to resume recording after app state change: \(error.localizedDescription)")
                }
                wasRecordingBeforeInterruption = false
            } else if isRecording {
                // Recording continued in background, just notify
                notifyListeners("recordingInterruption", data: [
                    "message": "App returned to foreground - recording continued"
                ])
            }
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

    // Helper method to get recorder settings with default values
    private func getRecorderSettings() -> [String: Any] {
        let settings = audioRecorder?.settings ?? [:]

        // Ensure default values are provided if settings are missing
        var result: [String: Any] = [:]
        result[AVSampleRateKey] = settings[AVSampleRateKey] as? Double ?? 44100.0
        result[AVNumberOfChannelsKey] = settings[AVNumberOfChannelsKey] as? Int ?? 1
        result[AVEncoderBitRateKey] = settings[AVEncoderBitRateKey] as? Int ?? 128000

        return result
    }

    // Helper method to get sample rate from settings
    private func getSampleRate() -> Double {
        return getRecorderSettings()[AVSampleRateKey] as? Double ?? 44100.0
    }

    // Helper method to get channels from settings
    private func getChannels() -> Int {
        return getRecorderSettings()[AVNumberOfChannelsKey] as? Int ?? 1
    }

    // Helper method to get bitrate from settings
    private func getBitrate() -> Int {
        return getRecorderSettings()[AVEncoderBitRateKey] as? Int ?? 128000
    }

    // Helper method to create recording response
    private func createRecordingResponse(fileToReturn: URL, fileSize: Int64, modificationDate: Date, durationInSeconds: Double) -> [String: Any] {
        // Get settings using helper methods
        let sampleRate = getSampleRate()
        let channels = getChannels()
        let bitrate = getBitrate()

        // Round to 1 decimal place for better display
        let roundedDuration = round(durationInSeconds * 10) / 10

        // Encode audio file to base64 with MIME prefix (Data URI format)
        var base64Audio: String?
        do {
            let audioData = try Data(contentsOf: fileToReturn)
            let base64String = audioData.base64EncodedString()
            base64Audio = "data:audio/m4a;base64," + base64String
        } catch {
            print("Failed to encode audio file to base64: \(error.localizedDescription)")
            base64Audio = nil
        }

        return [
            "path": fileToReturn.path,
            "uri": fileToReturn.absoluteString,
            "webPath": "capacitor://localhost/_capacitor_file_" + fileToReturn.path,
            "mimeType": "audio/m4a", // M4A container with AAC audio
            "size": fileSize,
            "duration": roundedDuration,
            "sampleRate": Int(sampleRate), // Cast to Int for consistency
            "channels": channels,
            "bitrate": bitrate,
            "createdAt": Int(modificationDate.timeIntervalSince1970 * 1000), // Milliseconds timestamp
            "filename": fileToReturn.lastPathComponent,
            "base64": base64Audio
        ]
    }

    private func startDurationMonitoring() {
        // Stop any existing timer
        stopDurationMonitoring()

        print("Starting duration monitoring")

        // Use dispatch queue for more reliable timing
        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        // Configure timer to fire every 1000ms (1 second)
        dispatchSource.schedule(deadline: .now(), repeating: .seconds(1))

        // Set event handler
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self,
                  let recorder = self.audioRecorder,
                  self.isRecording else { return }

            // Get current time
            let duration = max(0, recorder.currentTime)

            // Increment currentDuration by 1 second each time the timer fires
            // This ensures consistent duration tracking across segments
            self.currentDuration += 1.0

            self.lastReportedDuration = duration

            print("[iOS] Duration changed: \(duration) seconds, currentDuration: \(self.currentDuration) seconds")

            // Must dispatch to main queue for UI updates
            DispatchQueue.main.async {
                // Convert to integer by truncating decimal places
                let integerDuration = Int(self.currentDuration)
                print("Emitting durationChange event with integer duration: \(integerDuration)")
                self.notifyListeners("durationChange", data: [
                    "duration": integerDuration
                ])
            }
        }

        // Store the dispatch source and start it
        self.durationDispatchSource = dispatchSource
        dispatchSource.resume()

        print("Duration monitoring started with dispatch source timer")
    }

    private func stopDurationMonitoring() {
        print("Stopping duration monitoring")

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
        print("Stopping segment timer")

        // Get associated dispatch source
        if let timer = segmentTimer,
           let dispatchSource = objc_getAssociatedObject(timer, "dispatchSource") as? DispatchSourceTimer {
            dispatchSource.cancel()
        }

        // Clean up timer
        segmentTimer?.invalidate()
        segmentTimer = nil

        print("Segment timer stopped")
    }

    @objc func startRecording(_ call: CAPPluginCall) {
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
        let sampleRate = call.getInt("sampleRate") ?? 44100
        let channels = call.getInt("channels") ?? 1
        let bitrate = call.getInt("bitrate") ?? 128000
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

            let ext = ".m4a"
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
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
        print("Starting next segment. Current segment count: \(segmentFiles.count)")

        // Stop current recorder if exists
        if let recorder = audioRecorder, recorder.isRecording {
            print("Stopping current recorder")
            recorder.stop()
        }

        // Create new segment file
        let ext = ".m4a"
        let timestamp = Int(Date().timeIntervalSince1970 * 1000)
        let segmentFilename = recordingsPath.appendingPathComponent("segment_\(timestamp)\(ext)")
        print("Creating new segment file: \(segmentFilename.lastPathComponent)")

        // Add to segments list
        segmentFiles.append(segmentFilename)
        currentSegment += 1
        print("Current segment number: \(currentSegment)")

        // Keep only last 2 segments
        while segmentFiles.count > 2 {
            let oldSegmentURL = segmentFiles.removeFirst()
            try? FileManager.default.removeItem(at: oldSegmentURL)
            print("Removed old segment: \(oldSegmentURL.lastPathComponent)")
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
                print(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
                return
            }

            if !recorder.prepareToRecord() {
                let errorMsg = "Failed to prepare segment recorder"
                print(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
                return
            }

            let recordingStarted = recorder.record()
            if !recordingStarted {
                let errorMsg = "Failed to start segment recording"
                print(errorMsg)
                notifyListeners("error", data: ["message": errorMsg])
            } else {
                print("Successfully started recording segment: \(segmentFilename.lastPathComponent)")
            }
        } catch {
            let errorMsg = "Failed to create segment recorder: \(error.localizedDescription)"
            print(errorMsg)
            notifyListeners("error", data: ["message": errorMsg])
        }
    }

    private func startSegmentTimer(maxDuration: Int) {
        // Cancel existing timer
        segmentTimer?.invalidate()

        print("Starting segment timer with maxDuration: \(maxDuration) seconds")

        // Use dispatch queue for more reliable timing
        let dispatchQueue = DispatchQueue.global(qos: .userInteractive)
        let dispatchSource = DispatchSource.makeTimerSource(queue: dispatchQueue)

        // Configure timer to fire every maxDuration seconds
        dispatchSource.schedule(deadline: .now() + .seconds(maxDuration), repeating: .seconds(maxDuration))

        // Set event handler
        dispatchSource.setEventHandler { [weak self] in
            guard let self = self, self.isRecording else {
                print("Timer fired but recording is not active, ignoring")
                return
            }

            print("Segment timer fired after \(maxDuration) seconds")

            // Execute on main thread since we're modifying UI-related state
            DispatchQueue.main.async {
                // Get current recording settings
                guard let recorder = self.audioRecorder else {
                    print("Timer fired but recorder is nil, ignoring")
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

                print("Creating next segment after timer interval")
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
        objc_setAssociatedObject(self.segmentTimer!, "dispatchSource", dispatchSource, .OBJC_ASSOCIATION_RETAIN)

        print("Segment timer started successfully")
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        if let recorder = audioRecorder, isRecording { // isRecording is the plugin's overall session state
            if recorder.isRecording { // Check if it's actually recording (not already paused)
                print("Pausing recording")

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

        print("Resuming recording")

        // If it's paused (plugin's isRecording is true, but recorder.isRecording is false)
        do {
            // Ensure audio session is active
            try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

            let recordingResumed = recorder.record() // AVAudioRecorder's record method resumes if paused
            if recordingResumed {
                // recorder.isRecording will now be true

                // Restart segment timer if maxDuration is set
                if let maxDuration = maxDuration, maxDuration > 0 {
                    print("Restarting segment timer after resume")
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
            print("Processing segments. Total segments: \(segmentFiles.count)")

            // Check if we have at least 2 segments and the last segment is shorter than maxDuration
            if segmentFiles.count >= 2 {
                let lastSegment = segmentFiles.last!
                let previousSegment = segmentFiles[segmentFiles.count - 2]

                // Get duration of the last segment
                let lastSegmentAsset = AVAsset(url: lastSegment)
                let lastSegmentDuration = lastSegmentAsset.duration.seconds

                print("Last segment duration: \(lastSegmentDuration) seconds, maxDuration: \(maxDuration) seconds")

                // If last segment is shorter than maxDuration, merge with previous segment
                if lastSegmentDuration < Double(maxDuration) {
                    print("Last segment is shorter than maxDuration, merging with previous segment")

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
                    print("Required duration from previous segment: \(requiredDuration) seconds")

                    // Get previous segment duration to validate
                    let previousSegmentAsset = AVAsset(url: previousSegment)
                    let previousSegmentDuration = previousSegmentAsset.duration.seconds

                    // Make sure we don't request more than what's available
                    let validRequiredDuration = min(requiredDuration, previousSegmentDuration)
                    print("Valid required duration: \(validRequiredDuration) seconds (previous segment duration: \(previousSegmentDuration) seconds)")

                    // Merge the audio files asynchronously
                    mergeAudioFiles(firstFile: previousSegment, secondFile: lastSegment, outputFile: mergedFilePath, requiredDuration: validRequiredDuration) { result in
                        switch result {
                        case .success:
                            fileToReturn = mergedFilePath
                            print("Successfully merged segments into: \(mergedFilePath.path)")

                            // Clean up the segments that were merged
                            try? FileManager.default.removeItem(at: previousSegment)
                            try? FileManager.default.removeItem(at: lastSegment)
                            print("Removed original segments after merging")

                            // Remove the merged segments from the list
                            self.segmentFiles.removeAll { $0 == previousSegment || $0 == lastSegment }

                            // Add the merged file to the list
                            self.segmentFiles.append(mergedFilePath)

                        case .failure(let error):
                            print("Failed to merge audio segments: \(error.localizedDescription)")
                            fileToReturn = lastSegment
                        }

                        // Clean up other segments
                        for segmentPath in self.segmentFiles {
                            if segmentPath != fileToReturn {
                                try? FileManager.default.removeItem(at: segmentPath)
                                print("Removed unused segment: \(segmentPath.lastPathComponent)")
                            }
                        }
                        self.segmentFiles.removeAll()
                        print("Final recording file: \(fileToReturn.path)")

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

                            print("Original recording duration: \(durationInSeconds) seconds")

                            print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

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
                    print("Last segment is not shorter than maxDuration, using as is")
                    fileToReturn = lastSegment

                    // Process the file asynchronously to maintain consistent pattern
                    DispatchQueue.global(qos: .userInitiated).async {
                        // Clean up other segments
                        for segmentPath in self.segmentFiles {
                            if segmentPath != fileToReturn {
                                try? FileManager.default.removeItem(at: segmentPath)
                                print("Removed unused segment: \(segmentPath.lastPathComponent)")
                            }
                        }
                        self.segmentFiles.removeAll()
                        print("Final recording file: \(fileToReturn.path)")

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

                            print("Original recording duration: \(durationInSeconds) seconds")

                            print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

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
                print("Only one segment available, using it as is")
                fileToReturn = segmentFiles.last ?? path

                // Process the file asynchronously to maintain consistent pattern
                DispatchQueue.global(qos: .userInitiated).async {
                    // Clean up other segments
                    for segmentPath in self.segmentFiles {
                        if segmentPath != fileToReturn {
                            try? FileManager.default.removeItem(at: segmentPath)
                            print("Removed unused segment: \(segmentPath.lastPathComponent)")
                        }
                    }
                    self.segmentFiles.removeAll()
                    print("Final recording file: \(fileToReturn.path)")

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

                        print("Original recording duration: \(durationInSeconds) seconds")

                        print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")

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
        }
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        if isRecording || audioRecorder != nil {
            // Return the current duration as an integer
            // Make sure we're returning the most up-to-date duration value
            let intDuration = Int(currentDuration)
            print("getDuration returning: \(intDuration) seconds")
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
        guard let sourcePath = call.getString("uri") else {
            call.reject("Source URI is required")
            return
        }

        let startTime = call.getDouble("start") ?? 0.0
        let endTime = call.getDouble("end") ?? 0.0

        if endTime <= startTime {
            call.reject("End time must be greater than start time")
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
                                print("Failed to encode trimmed audio file to base64: \(error.localizedDescription)")
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
                                "base64": base64Audio
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
            let firstFormat = firstTrack.formatDescriptions.first as! CMFormatDescription
            let secondFormat = secondTrack.formatDescriptions.first as! CMFormatDescription
            let firstASBD = CMAudioFormatDescriptionGetStreamBasicDescription(firstFormat)
            let secondASBD = CMAudioFormatDescriptionGetStreamBasicDescription(secondFormat)

            if let firstASBD = firstASBD, let secondASBD = secondASBD {
                if firstASBD.pointee.mSampleRate != secondASBD.pointee.mSampleRate {
                    print("Warning: Sample rate mismatch between segments: \(firstASBD.pointee.mSampleRate) vs \(secondASBD.pointee.mSampleRate)")
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

            print("Merging audio files:")
            print("First file duration: \(firstFileDuration) seconds")
            print("Required duration: \(requiredDuration) seconds")
            print("Valid required duration: \(validRequiredDuration) seconds")
            print("Start time in first file: \(startTime) seconds")
            print("First portion duration: \(firstPortionDuration) seconds")
            print("Second file duration: \(secondAsset.duration.seconds) seconds")

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

            print("First segment end time: \(CMTimeGetSeconds(firstSegmentEndTime)) seconds")
            print("Second segment start time: \(CMTimeGetSeconds(secondSegmentStartTime)) seconds")

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
            let crossfadeDuration = 0.02 // 20 milliseconds crossfade

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
                        print("Successfully merged audio files to: \(outputFile.path)")
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

    // Cleanup when plugin is destroyed
    deinit {
        print("Plugin is being destroyed, cleaning up resources")

        // Stop duration monitoring
        stopDurationMonitoring()

        // Clean up segment timer properly
        stopSegmentTimer()

        // Stop recording if active
        if let recorder = audioRecorder {
            if recorder.isRecording {
                print("Stopping active recording during cleanup")
                recorder.stop()
            }
            audioRecorder = nil
        }
        isRecording = false

        // Cancel export if in progress
        if let session = exportSession {
            print("Canceling active export session during cleanup")
            session.cancelExport()
        }
        exportSession = nil

        // Deactivate audio session
        try? recordingSession?.setActive(false)
        recordingSession = nil

        // Stop monitoring for interruptions
        stopInterruptionMonitoring()

        // Clean up segment files
        print("Cleaning up \(segmentFiles.count) segment files")
        for segmentPath in segmentFiles {
            try? FileManager.default.removeItem(at: segmentPath)
            print("Removed segment file: \(segmentPath.lastPathComponent)")
        }
        segmentFiles.removeAll()

        // Clean up playback resources
        audioPlayer?.stop()
        audioPlayer = nil
        urlSessionTask?.cancel()
        urlSessionTask = nil
        stopPlaybackProgressTimer()

        print("Plugin cleanup completed")
    }

    @objc public override func addListener(_ call: CAPPluginCall) {
        guard let eventName = call.getString("eventName") else {
            call.reject("Event name is required")
            return
        }

        // Validate event name - include both recording and playback events
        switch eventName {
        case "recordingInterruption", "durationChange", "error":
            print("Adding recording listener for \(eventName)")
            super.addListener(call)

            if eventName == "durationChange" {
                print("Duration change listener added")

                // If we're recording, immediately restart duration monitoring to ensure events fire
                if isRecording {
                    print("Restarting duration monitoring because listener was added while recording")
                    stopDurationMonitoring() // Ensure clean state
                    startDurationMonitoring()

                    // Also emit an immediate event with the current duration
                    let intDuration = Int(currentDuration)
                    print("Emitting immediate durationChange event with current duration: \(intDuration)")
                    notifyListeners("durationChange", data: [
                        "duration": intDuration
                    ])
                }
            }

            call.resolve()
        case "playbackProgress", "playbackStatusChange", "playbackCompleted", "playbackError":
            print("Adding playback listener for \(eventName)")
            super.addListener(call)
            call.resolve()
        default:
            call.reject("Unknown event name: \(eventName)")
        }
    }

    @objc public override func removeAllListeners(_ call: CAPPluginCall) {
        print("Removing all listeners")
        super.removeAllListeners(call)
        call.resolve()
    }

    @objc public func removeAllListeners() {
        print("Removing all listeners (no-arg version)")
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
                print("Failed to handle interruption: \(error.localizedDescription)")
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
                        print("Successfully resumed recording after interruption")

                        // Restart segment timer if maxDuration is set
                        if let maxDuration = maxDuration, maxDuration > 0 {
                            print("Restarting segment timer after interruption")
                            startSegmentTimer(maxDuration: maxDuration)
                        }

                        // Restart duration monitoring after interruption
                        startDurationMonitoring()

                        notifyListeners("recordingInterruption", data: [
                            "message": "Recording resumed after interruption"
                        ])
                    } else {
                        print("Failed to resume recording after interruption")
                        wasRecordingBeforeInterruption = false

                        notifyListeners("recordingInterruption", data: [
                            "message": "Failed to resume recording after interruption"
                        ])
                    }
                }
            } catch {
                print("Failed to resume recording after app state change: \(error.localizedDescription)")
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

        do {
            let audioSession = AVAudioSession.sharedInstance()

            // Check if another app is currently using the microphone
            if audioSession.isOtherAudioPlaying {
                isBusy = true
            }

            // Check if we're currently recording
            if audioRecorder?.isRecording == true {
                isBusy = true
            }

            // Additional check: try to create a temporary audio recorder to test availability
            if !isBusy {
                let tempURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("temp_test.m4a")
                let settings = [
                    AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                    AVSampleRateKey: 44100,
                    AVNumberOfChannelsKey: 1,
                    AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue
                ]

                do {
                    let tempRecorder = try AVAudioRecorder(url: tempURL, settings: settings)
                    if !tempRecorder.prepareToRecord() {
                        isBusy = true
                    }

                    // Clean up temp file
                    try? FileManager.default.removeItem(at: tempURL)
                } catch {
                    isBusy = true
                }
            }

            call.resolve([
                "busy": isBusy,
                "reason": isBusy ? "Microphone is currently in use by another application" : "Microphone is available"
            ])
        } catch {
            call.reject("Failed to check microphone status: \(error.localizedDescription)")
        }
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
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]

        do {
            audioRecorder = try AVAudioRecorder(url: recordingPath, settings: settings)
            audioRecorder?.prepareToRecord()
        } catch {
            print("Failed to setup audio recorder: \(error)")
        }
    }

    // MARK: - Audio Playback Methods

    @objc func preload(_ call: CAPPluginCall) {
        guard let uri = call.getString("uri") else {
            call.reject("URI is required")
            return
        }

        let prepare = call.getBool("prepare") ?? true

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

                // Check if it's a remote URL
                if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
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
        guard let url = URL(string: uri) else {
            DispatchQueue.main.async {
                call.reject("Invalid remote URL")
            }
            return
        }

        // Create URLRequest with proper headers for CDN compatibility
        var request = URLRequest(url: url)
        request.setValue("CapacitorAudioEngine/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue("audio/*", forHTTPHeaderField: "Accept")
        request.setValue("bytes", forHTTPHeaderField: "Accept-Ranges")
        request.timeoutInterval = 30.0

        // Download audio for preloading
        self.urlSessionTask = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }

                if let error = error {
                    call.reject("Failed to preload audio: \(error.localizedDescription)")
                    return
                }

                guard let data = data else {
                    call.reject("No audio data received for preload")
                    return
                }

                // Check HTTP response
                if let httpResponse = response as? HTTPURLResponse {
                    guard 200...299 ~= httpResponse.statusCode else {
                        call.reject("Failed to preload audio: HTTP error \(httpResponse.statusCode)")
                        return
                    }
                }

                // Create audio player from downloaded data
                do {
                    self.audioPlayer = try AVAudioPlayer(data: data)
                    self.audioPlayer?.delegate = self
                    self.currentPlaybackPath = uri

                    if prepare {
                        let prepared = self.audioPlayer?.prepareToPlay() ?? false
                        if prepared {
                            call.resolve(["success": true])
                        } else {
                            call.reject("Failed to prepare remote audio for playback")
                        }
                    } else {
                        call.resolve(["success": true])
                    }

                } catch {
                    call.reject("Failed to preload audio: \(error.localizedDescription)")
                }
            }
        }

        self.urlSessionTask?.resume()
    }

    private func preloadLocalAudio(uri: String, prepare: Bool, call: CAPPluginCall) {
        let url = URL(string: uri) ?? URL(fileURLWithPath: uri)

        do {
            self.audioPlayer = try AVAudioPlayer(contentsOf: url)
            self.audioPlayer?.delegate = self
            self.currentPlaybackPath = uri

            if prepare {
                let prepared = self.audioPlayer?.prepareToPlay() ?? false
                DispatchQueue.main.async {
                    if prepared {
                        call.resolve(["success": true])
                    } else {
                        call.reject("Failed to prepare local audio for playback")
                    }
                }
            } else {
                DispatchQueue.main.async {
                    call.resolve(["success": true])
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

                // Get playback options
                let speed = call.getFloat("speed") ?? 1.0
                let volume = call.getFloat("volume") ?? 1.0
                let loop = call.getBool("loop") ?? false
                let startTime = call.getDouble("startTime") ?? 0.0

                self.playbackSpeed = speed
                self.playbackVolume = volume
                self.isLooping = loop

                // Check if it's a remote URL
                if uri.hasPrefix("http://") || uri.hasPrefix("https://") {
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
        guard let url = URL(string: uri) else {
            DispatchQueue.main.async {
                call.reject("Invalid remote URL")
            }
            return
        }

        // Create URLRequest with proper headers for CDN compatibility
        var request = URLRequest(url: url)
        request.setValue("CapacitorAudioEngine/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue("audio/*", forHTTPHeaderField: "Accept")
        request.setValue("bytes", forHTTPHeaderField: "Accept-Ranges")
        request.timeoutInterval = 30.0

        // Download and play audio
        self.urlSessionTask = URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }

                if let error = error {
                    call.reject("Network error: \(error.localizedDescription)")
                    return
                }

                guard let data = data else {
                    call.reject("No audio data received")
                    return
                }

                // Check HTTP response
                if let httpResponse = response as? HTTPURLResponse {
                    guard 200...299 ~= httpResponse.statusCode else {
                        call.reject("HTTP error: \(httpResponse.statusCode)")
                        return
                    }
                }

                // Create audio player from downloaded data
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
                        call.reject("Failed to prepare audio for playback")
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

                        // Notify listeners
                        let eventData: [String: Any] = [
                            "status": "playing",
                            "currentTime": self.audioPlayer?.currentTime ?? 0,
                            "duration": self.audioPlayer?.duration ?? 0
                        ]
                        self.notifyListeners("playbackStatusChange", data: eventData)
                        call.resolve()
                    } else {
                        call.reject("Failed to start remote audio playback")
                    }

                } catch {
                    call.reject("Failed to create audio player from remote data: \(error.localizedDescription)")
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
        guard let audioPlayer = audioPlayer else {
            call.reject("No active playback to resume")
            return
        }

        if isPlaying {
            call.reject("Playback is already active")
            return
        }

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

    // MARK: - Playback Helper

    private func startPlaybackProgressTimer() {
        stopPlaybackProgressTimer()

        // Ensure timer is created on main queue for proper execution
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            print("iOS: Starting playback progress timer")

            self.playbackProgressTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
                guard let self = self,
                      let audioPlayer = self.audioPlayer,
                      self.isPlaying else {
                    print("iOS: Timer fired but conditions not met - isPlaying: \(self?.isPlaying ?? false), audioPlayer: \(self?.audioPlayer != nil)")
                    return
                }

                let currentTime = audioPlayer.currentTime
                let duration = audioPlayer.duration
                let position = duration > 0 ? (currentTime / duration) * 100 : 0

                let eventData: [String: Any] = [
                    "currentTime": currentTime,
                    "duration": duration,
                    "position": position
                ]

                print("iOS: Emitting playbackProgress - currentTime: \(currentTime), duration: \(duration), position: \(position)")
                self.notifyListeners("playbackProgress", data: eventData)
            }

            // Ensure timer is added to the run loop
            if let timer = self.playbackProgressTimer {
                RunLoop.main.add(timer, forMode: .common)
                print("iOS: Timer added to main run loop")
            }
        }
    }

    private func stopPlaybackProgressTimer() {
        DispatchQueue.main.async { [weak self] in
            print("iOS: Stopping playback progress timer")
            self?.playbackProgressTimer?.invalidate()
            self?.playbackProgressTimer = nil
        }
    }
}