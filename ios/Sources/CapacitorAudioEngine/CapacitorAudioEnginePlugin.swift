import Foundation
import Capacitor
import AVFoundation
import AVKit
import UIKit

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
        CAPPluginMethod(name: "startMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopMonitoring", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "addListener", returnType: CAPPluginReturnCallback)
    ]

    private var interruptionMonitor: RecordingInterruptionMonitor?
    private var hasListeners = false

    private var audioRecorder: AVAudioRecorder?
    private var recordingSession: AVAudioSession?
    private var isRecording = false
    private var recordingPath: URL?
    private var exportSession: AVAssetExportSession?

    private var wasRecordingBeforeInterruption = false

    @objc func checkPermission(_ call: CAPPluginCall) {
        let status = AVAudioSession.sharedInstance().recordPermission
        call.resolve([
            "granted": status == .granted
        ])
    }

    override public func load() {
        interruptionMonitor = RecordingInterruptionMonitor(plugin: self)
    }

    @objc func requestPermission(_ call: CAPPluginCall) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            call.resolve([
                "granted": granted
            ])
        }
    }

    @objc func startRecording(_ call: CAPPluginCall) {
        if audioRecorder != nil {
            call.reject("Recording is already in progress")
            return
        }

        do {
            // Start monitoring for interruptions
            interruptionMonitor?.startMonitoring()

            recordingSession = AVAudioSession.sharedInstance()
            try recordingSession?.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

            // Configure audio session for recording
            try recordingSession?.overrideOutputAudioPort(.speaker)

            // Create Recordings directory if it doesn't exist
            let fileManager = FileManager.default
            let documentsPath = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
            let recordingsPath = documentsPath.appendingPathComponent("Recordings", isDirectory: true)

            do {
                try fileManager.createDirectory(at: recordingsPath, withIntermediateDirectories: true, attributes: nil)
            } catch {
                call.reject("Failed to create recordings directory: \(error.localizedDescription)")
                return
            }

            // Create unique filename with timestamp
            let timestamp = Int(Date().timeIntervalSince1970 * 1000)
            let audioFilename = recordingsPath.appendingPathComponent("recording_\(timestamp).m4a")
            recordingPath = audioFilename

            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44100.0,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
                AVEncoderBitRateKey: 128000
            ]

            audioRecorder = try AVAudioRecorder(url: audioFilename, settings: settings)

            guard let recorder = audioRecorder else {
                call.reject("Failed to initialize audio recorder")
                return
            }

            recorder.prepareToRecord()
            let recordingStarted = recorder.record()

            if recordingStarted {
                isRecording = true
                call.resolve()
            } else {
                call.reject("Failed to start recording")
            }
        } catch {
            call.reject("Failed to start recording: \(error.localizedDescription)")
        }
    }

    @objc func pauseRecording(_ call: CAPPluginCall) {
        if let recorder = audioRecorder, isRecording { // isRecording is the plugin's overall session state
            if recorder.isRecording { // Check if it's actually recording (not already paused)
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

        // If it's paused (plugin's isRecording is true, but recorder.isRecording is false)
        do {
            // Ensure audio session is active
            try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)

            let recordingResumed = recorder.record() // AVAudioRecorder's record method resumes if paused
            if recordingResumed {
                // recorder.isRecording will now be true
                call.resolve()
            } else {
                call.reject("Failed to resume recording.")
            }
        } catch {
            call.reject("Failed to set audio session active for resume: \(error.localizedDescription)")
        }
    }

    @objc func stopRecording(_ call: CAPPluginCall) {
        guard let recorder = audioRecorder, isRecording else {
            call.reject("No active recording")
            return
        }

        // Stop monitoring for interruptions
        interruptionMonitor?.stopMonitoring()

        recorder.stop()
        isRecording = false

        guard let path = recordingPath else {
            call.reject("Recording path not found")
            return
        }

        do {
            // Get file attributes
            let fileAttributes = try FileManager.default.attributesOfItem(atPath: path.path)
            let fileSize = fileAttributes[.size] as? Int64 ?? 0

            // Get audio metadata
            let asset = AVAsset(url: path)
            let duration = asset.duration.seconds

            // Create a temporary directory URL that's accessible via web
            let tempDir = FileManager.default.temporaryDirectory
            let tempFile = tempDir.appendingPathComponent(path.lastPathComponent)

            // Copy the file to temporary directory
            if FileManager.default.fileExists(atPath: tempFile.path) {
                try FileManager.default.removeItem(at: tempFile)
            }
            try FileManager.default.copyItem(at: path, to: tempFile)

            // Create response object with formatted web path
            let response: [String: Any] = [
                "path": path.path,
                "uri": tempFile.absoluteString,
                "webPath": "capacitor://localhost/_capacitor_file_" + tempFile.path,
                "mimeType": "audio/m4a",
                "size": fileSize,
                "duration": duration,
                "sampleRate": 44100,
                "channels": 1,
                "bitrate": 128000,
                "createdAt": Int(Date().timeIntervalSince1970 * 1000),
                "filename": path.lastPathComponent
            ]

            call.resolve(response)
        } catch {
            call.reject("Failed to get recording details: \(error.localizedDescription)")
        }

        // Cleanup
        audioRecorder = nil
    }

    @objc func getDuration(_ call: CAPPluginCall) {
        if let recorder = audioRecorder {
            call.resolve([
                "duration": recorder.currentTime
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

        call.resolve([
            "status": currentStatus, // "idle", "recording", "paused"
            "isRecording": sessionActive && (currentStatus == "recording" || currentStatus == "paused")
        ])
    }

    @objc func trimAudio(_ call: CAPPluginCall) {
        guard let sourcePath = call.getString("path") else {
            call.reject("Source path is required")
            return
        }

        let startTime = call.getDouble("startTime") ?? 0.0
        let endTime = call.getDouble("endTime") ?? 0.0

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
                            try FileManager.default.copyItem(at: outputURL, to: tempFile)

                            // Get audio metadata
                            let trimmedAsset = AVAsset(url: outputURL)
                            let duration = trimmedAsset.duration.seconds

                            // Create response object
                            let response: [String: Any] = [
                                "path": outputURL.path,
                                "uri": tempFile.absoluteString,
                                "webPath": "capacitor://localhost/_capacitor_file_" + tempFile.path,
                                "mimeType": "audio/m4a",
                                "size": fileSize,
                                "duration": duration,
                                "sampleRate": 44100,
                                "channels": 1,
                                "bitrate": 128000,
                                "createdAt": Int(Date().timeIntervalSince1970 * 1000),
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

    // Cleanup when plugin is destroyed
    deinit {
        if let recorder = audioRecorder {
            recorder.stop()
            audioRecorder = nil
        }
        isRecording = false
        try? recordingSession?.setActive(false)
    }

    @objc func startMonitoring(_ call: CAPPluginCall) {
        interruptionMonitor?.startMonitoring()
        hasListeners = true
        call.resolve()
    }

    @objc func stopMonitoring(_ call: CAPPluginCall) {
        interruptionMonitor?.stopMonitoring()
        hasListeners = false
        call.resolve()
    }

    @objc  public override func addListener(_ call: CAPPluginCall) {
        guard let eventName = call.getString("eventName") else {
            call.reject("Event name is required")
            return
        }

        if eventName == "recordingInterruption" {
            hasListeners = true
            call.resolve()
        } else {
            call.reject("Unknown event name: \(eventName)")
        }
    }

    // Add these public methods to handle recording state
    func pauseRecordingIfActive() {
        if isRecording {
            audioRecorder?.pause()
        }
    }

    func resumeRecordingIfActive() {
        if isRecording {
            do {
                try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)
                audioRecorder?.record()
            } catch {
                print("Failed to resume recording: \(error.localizedDescription)")
            }
        }
    }

    func isCurrentlyRecording() -> Bool {
        return isRecording
    }

    // Add these methods to handle interruptions
    public func handleInterruptionBegan() {
        if isRecording {
            audioRecorder?.pause()
            wasRecordingBeforeInterruption = true
        }
    }

    public func handleInterruptionEnded(shouldResume: Bool) {
        if wasRecordingBeforeInterruption && shouldResume {
            do {
                try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)
                audioRecorder?.record()
            } catch {
                print("Failed to resume recording after interruption: \(error.localizedDescription)")
            }
        }
    }

    public func handleAppStateChange(isBackground: Bool) {
        if isBackground {
            if isRecording {
                audioRecorder?.pause()
                wasRecordingBeforeInterruption = true
            }
        } else {
            if wasRecordingBeforeInterruption {
                do {
                    try recordingSession?.setActive(true, options: .notifyOthersOnDeactivation)
                    audioRecorder?.record()
                } catch {
                    print("Failed to resume recording after app state change: \(error.localizedDescription)")
                }
            }
        }
    }
}

/**
 * Handles audio recording interruptions and route changes
 */
public class RecordingInterruptionMonitor {
    private var observers: [NSObjectProtocol] = []
    private var hasListeners = false
    private weak var plugin: CapacitorAudioEnginePlugin?

    public init(plugin: CapacitorAudioEnginePlugin) {
        self.plugin = plugin
    }

    public func startMonitoring() {
        hasListeners = true
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
        observers.append(interruptionObserver)

        // Audio route change notifications
        let routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance(),
            queue: .main
        ) { [weak self] notification in
            print("Received route change notification") // Debug log
            self?.handleRouteChange(notification)
        }
        observers.append(routeChangeObserver)

        // App state change notifications
        let willResignActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App will resign active") // Debug log
            self?.handleAppStateChange("App moved to background")
        }
        observers.append(willResignActiveObserver)

        let didEnterBackgroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App did enter background") // Debug log
            self?.handleAppStateChange("App entered background")
        }
        observers.append(didEnterBackgroundObserver)

        let didBecomeActiveObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            print("App did become active") // Debug log
            self?.handleAppStateChange("App became active")
        }
        observers.append(didBecomeActiveObserver)
    }

    public func stopMonitoring() {
        print("Stopping interruption monitoring") // Debug log
        hasListeners = false
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
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
            plugin?.pauseRecordingIfActive()
            notifyListeners("recordingInterruption", data: ["message": "Interruption began"])

        case .ended:
            if let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt {
                let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
                if options.contains(.shouldResume) {
                    plugin?.resumeRecordingIfActive()
                    notifyListeners("recordingInterruption", data: ["message": "Interruption ended - should resume"])
                } else {
                    notifyListeners("recordingInterruption", data: ["message": "Interruption ended - should not resume"])
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

        switch reason {
        case .oldDeviceUnavailable:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: headphones unplugged"])
        case .newDeviceAvailable:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: new device connected"])
        case .categoryChange:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: category changed"])
        case .override:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: route overridden"])
        case .wakeFromSleep:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: device woke from sleep"])
        case .noSuitableRouteForCategory:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: no suitable route for category"])
        case .routeConfigurationChange:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: configuration changed"])
        default:
            notifyListeners("recordingInterruption", data: ["message": "Audio route changed: unknown reason"])
        }
    }

    private func handleAppStateChange(_ message: String) {
        if message.contains("App moved to background") || message.contains("App entered background") {
            plugin?.pauseRecordingIfActive()
        } else if message.contains("App became active") {
            plugin?.resumeRecordingIfActive()
        }
        notifyListeners("recordingInterruption", data: ["message": message])
    }

    private func notifyListeners(_ eventName: String, data: [String: Any]) {
        guard hasListeners else { return }
        print("Notifying listeners: \(eventName) with data: \(data)") // Debug log
        plugin?.notifyListeners(eventName, data: data)
    }
}
