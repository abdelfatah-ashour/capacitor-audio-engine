import Foundation
import Capacitor
import AVFoundation
import AVKit
import UIKit
import ObjectiveC

/**
 * Please read the Capacitor iOS Plugin Development Guide
 * here: https://capacitorjs.com/docs/plugins/ios
 */
@objc(CapacitorAudioEnginePlugin)
public class CapacitorAudioEnginePlugin: CAPPlugin, CAPBridgedPlugin {
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

    @objc func checkPermission(_ call: CAPPluginCall) {
        let status = AVAudioSession.sharedInstance().recordPermission
        call.resolve([
            "granted": status == .granted
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
                "eventName": "recordingInterruption",
                "payload": ["message": "Interruption began"]
            ])

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                handleInterruptionEnded(shouldResume: options.contains(.shouldResume))

                if options.contains(.shouldResume) {
                    notifyListeners("recordingInterruption", data: [
                        "eventName": "recordingInterruption",
                        "payload": ["message": "Interruption ended - should resume"]
                    ])
                } else {
                    notifyListeners("recordingInterruption", data: [
                        "eventName": "recordingInterruption",
                        "payload": ["message": "Interruption ended - should not resume"]
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
            "eventName": "recordingInterruption",
            "payload": ["message": message]
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
                    "eventName": "recordingInterruption",
                    "payload": ["message": "App entered background - recording continues"]
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
                        "eventName": "recordingInterruption",
                        "payload": ["message": "Background recording failed - will resume when app returns"]
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
                                "eventName": "recordingInterruption",
                                "payload": ["message": "Recording resumed after returning to foreground"]
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
                    "eventName": "recordingInterruption",
                    "payload": ["message": "App returned to foreground - recording continued"]
                ])
            }
        }
    }

    @objc func requestPermission(_ call: CAPPluginCall) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            call.resolve([
                "granted": granted
            ])
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
            "filename": fileToReturn.lastPathComponent
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
                    "eventName": "durationChange",
                    "payload": ["duration": integerDuration]
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
            "eventName": "durationChange",
            "payload": ["duration": currentDuration]
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
            notifyListeners("error", data: [
                "eventName": "error",
                "payload": err
            ])
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
                notifyListeners("error", data: [
                    "eventName": "error",
                    "payload": err
                ])
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
                notifyListeners("error", data: [
                    "eventName": "error",
                    "payload": ["message": errorMsg]
                ])
                return
            }

            if !recorder.prepareToRecord() {
                let errorMsg = "Failed to prepare segment recorder"
                print(errorMsg)
                notifyListeners("error", data: [
                    "eventName": "error",
                    "payload": ["message": errorMsg]
                ])
                return
            }

            let recordingStarted = recorder.record()
            if !recordingStarted {
                let errorMsg = "Failed to start segment recording"
                print(errorMsg)
                notifyListeners("error", data: [
                    "eventName": "error",
                    "payload": ["message": errorMsg]
                ])
            } else {
                print("Successfully started recording segment: \(segmentFilename.lastPathComponent)")
            }
        } catch {
            let errorMsg = "Failed to create segment recorder: \(error.localizedDescription)"
            print(errorMsg)
            notifyListeners("error", data: [
                "eventName": "error",
                "payload": ["message": errorMsg]
            ])
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

                            // Retrieve settings used for recording. Recorder instance is still valid here.
                            let currentSettings = recorder.settings
                            let sampleRate = currentSettings[AVSampleRateKey] as? Double ?? 44100.0
                            let channels = currentSettings[AVNumberOfChannelsKey] as? Int ?? 1
                            let bitrate = currentSettings[AVEncoderBitRateKey] as? Int ?? 128000

                            print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")
                            // Round to 1 decimal place for better display
                            let roundedDuration = round(durationInSeconds * 10) / 10

                            let response: [String: Any] = [
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
                                "filename": fileToReturn.lastPathComponent
                            ]
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

                            // Retrieve settings used for recording. Recorder instance is still valid here.
                            let currentSettings = recorder.settings
                            let sampleRate = currentSettings[AVSampleRateKey] as? Double ?? 44100.0
                            let channels = currentSettings[AVNumberOfChannelsKey] as? Int ?? 1
                            let bitrate = currentSettings[AVEncoderBitRateKey] as? Int ?? 128000

                            print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")
                            // Round to 1 decimal place for better display
                            let roundedDuration = round(durationInSeconds * 10) / 10

                            let response: [String: Any] = [
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
                                "filename": fileToReturn.lastPathComponent
                            ]
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

                        // Retrieve settings used for recording. Recorder instance is still valid here.
                        let currentSettings = recorder.settings
                        let sampleRate = currentSettings[AVSampleRateKey] as? Double ?? 44100.0
                        let channels = currentSettings[AVNumberOfChannelsKey] as? Int ?? 1
                        let bitrate = currentSettings[AVEncoderBitRateKey] as? Int ?? 128000

                        print("Returning recording at path: \(fileToReturn.path) with duration: \(durationInSeconds) seconds")
                        // Round to 1 decimal place for better display
                        let roundedDuration = round(durationInSeconds * 10) / 10

                        let response: [String: Any] = [
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
                            "filename": fileToReturn.lastPathComponent
                        ]
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
                                "filename": outputURL.lastPathComponent
                            ]

                            call.resolve(response)
                        } catch {
                            call.reject("Failed to process trimmed audio: \(error.localizedDescription)")
                        }
                    case .failed:
                        let errorMessage = exportSession.error?.localizedDescription ?? "Unknown error"
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

        print("Plugin cleanup completed")
    }

    @objc public override func addListener(_ call: CAPPluginCall) {
        guard let eventName = call.getString("eventName") else {
            call.reject("Event name is required")
            return
        }

        // Validate event name
        switch eventName {
        case "recordingInterruption", "durationChange", "error":
            print("Adding listener for \(eventName)")
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
                        "eventName": "durationChange",
                        "payload": ["duration": intDuration]
                    ])
                }
            }

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

                        // Restart duration monitoring when interruption ends
                        startDurationMonitoring()
                    } else {
                        print("Failed to resume recording after interruption")
                    }
                }
            } catch {
                print("Failed to resume recording after interruption: \(error.localizedDescription)")
            }
        }
        wasRecordingBeforeInterruption = false
    }

    @objc func isMicrophoneBusy(_ call: CAPPluginCall) {
        let audioSession = AVAudioSession.sharedInstance()

        // Check if another app is using the microphone
        let isBusy = audioSession.isOtherAudioPlaying ||
                     audioSession.secondaryAudioShouldBeSilencedHint

        call.resolve([
            "busy": isBusy
        ])
    }

    @objc func getAvailableMicrophones(_ call: CAPPluginCall) {
        let audioSession = AVAudioSession.sharedInstance()
        var microphones: [[String: Any]] = []

        // Get available audio inputs
        guard let availableInputs = audioSession.availableInputs else {
            call.resolve(["microphones": microphones])
            return
        }

        for (index, input) in availableInputs.enumerated() {
            var micType = "unknown"
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
                description = "Bluetooth Headset"
            case .usbAudio:
                micType = "external"
                description = "USB Audio"
            case .carAudio:
                micType = "external"
                description = "Car Audio"
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
}
