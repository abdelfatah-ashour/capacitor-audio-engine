import Foundation
import AVFoundation
import UserNotifications

/**
 * Standalone Permission Manager Service for iOS that handles audio recording permissions
 * with detailed status information and granular control.
 *
 * Provides comprehensive permission status mapping for iOS:
 * - GRANTED: Permission is granted permanently
 * - DENIED: Permission was denied permanently
 * - NOT_DETERMINED: Permission has never been requested
 * - LIMITED: Permission granted only for current session (iOS 14+)
 * - RESTRICTED: Permission restricted by device policy/parental controls
 */
@objc public class PermissionManagerService: NSObject {

    // MARK: - Types

    @objc public enum PermissionStatus: Int, CaseIterable {
        case granted
        case denied
        case deniedPermanently
        case notDetermined
        case limited
        case restricted
        case unsupported

        var stringValue: String {
            switch self {
            case .granted: return "granted"
            case .denied: return "denied"
            case .deniedPermanently: return "denied_permanently"
            case .notDetermined: return "not_determined"
            case .limited: return "limited"
            case .restricted: return "restricted"
            case .unsupported: return "unsupported"
            }
        }
    }

    @objc public enum PermissionType: Int, CaseIterable {
        case microphone
        case notifications

        var stringValue: String {
            switch self {
            case .microphone: return "microphone"
            case .notifications: return "notifications"
            }
        }
    }

    // MARK: - Properties

    private let userDefaults = UserDefaults.standard

    // MARK: - Public Methods

    /**
     * Check simplified permission status for all audio-related permissions
     */
    @objc public func checkPermissions() async -> [String: Any] {
        // Check microphone permission
        let microphoneStatus = await getMicrophonePermissionStatus()

        // Check notification permission
        let notificationStatus = await getNotificationPermissionStatus()

        // Determine overall granted status
        let microphoneGranted = microphoneStatus == .granted
        let notificationGranted = notificationStatus == .granted || notificationStatus == .unsupported
        let overallGranted = microphoneGranted && notificationGranted

        // Determine overall status - prioritize the most restrictive status
        let overallStatus: PermissionStatus
        if overallGranted {
            overallStatus = .granted
        } else if microphoneStatus == .deniedPermanently || notificationStatus == .deniedPermanently {
            overallStatus = .deniedPermanently
        } else if microphoneStatus == .denied || notificationStatus == .denied {
            overallStatus = .denied
        } else if microphoneStatus == .restricted || notificationStatus == .restricted {
            overallStatus = .restricted
        } else if microphoneStatus == .notDetermined || notificationStatus == .notDetermined {
            overallStatus = .notDetermined
        } else {
            overallStatus = .denied
        }

        return [
            "granted": overallGranted,
            "status": overallStatus.stringValue
        ]
    }

    /**
     * Check microphone permission status with simplified information
     */
    @objc public func checkPermissionMicrophone() async -> [String: Any] {
        let status = await getMicrophonePermissionStatus()
        let granted = status == .granted
        return [
            "granted": granted,
            "status": status.stringValue
        ]
    }

    /**
     * Check notification permission status with simplified information
     */
    @objc public func checkPermissionNotifications() async -> [String: Any] {
        let status = await getNotificationPermissionStatus()
        let granted = status == .granted || status == .unsupported
        return [
            "granted": granted,
            "status": status.stringValue
        ]
    }

    /**
     * Check status for a single permission type
     */
    private func checkSinglePermissionStatus(_ permissionType: PermissionType) async -> [String: Any] {
        var result: [String: Any] = [
            "permissionType": permissionType.stringValue
        ]

        let status = await getDetailedPermissionStatus(permissionType)
        result["status"] = status.stringValue

        // Add additional information
        switch status {
        case .granted:
            result["message"] = "Permission is granted"
            result["canRequestAgain"] = false
        case .denied:
            result["message"] = "Permission was denied"
            result["canRequestAgain"] = false
        case .deniedPermanently:
            result["message"] = "Permission was permanently denied. Please enable in Settings > Privacy & Security."
            result["canRequestAgain"] = false
        case .notDetermined:
            result["message"] = "Permission has not been requested yet"
            result["canRequestAgain"] = true
        case .limited:
            result["message"] = "Permission granted for current session only"
            result["canRequestAgain"] = true
        case .restricted:
            result["message"] = "Permission restricted by device policy or parental controls"
            result["canRequestAgain"] = false
        case .unsupported:
            result["message"] = "Permission not required on this iOS version"
            result["canRequestAgain"] = false
        }

        return result
    }

    /**
     * Get detailed permission status for a specific permission type
     */
    private func getDetailedPermissionStatus(_ permissionType: PermissionType) async -> PermissionStatus {
        switch permissionType {
        case .microphone:
            return await getMicrophonePermissionStatus()
        case .notifications:
            return await getNotificationPermissionStatus()
        }
    }

    /**
     * Get detailed microphone permission status
     */
    private func getMicrophonePermissionStatus() async -> PermissionStatus {
        let audioSession = AVAudioSession.sharedInstance()

        switch audioSession.recordPermission {
        case .granted:
            return .granted
        case .denied:
            // Check if it was ever requested to distinguish between denied and permanently denied
            return hasPermissionBeenRequested(.microphone) ? .deniedPermanently : .denied
        case .undetermined:
            return .notDetermined
        @unknown default:
            return .notDetermined
        }
    }

    /**
     * Get detailed notification permission status
     */
    private func getNotificationPermissionStatus() async -> PermissionStatus {
        // Notifications not available before iOS 10
        guard #available(iOS 10.0, *) else {
            return .unsupported
        }

        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()

        switch settings.authorizationStatus {
        case .authorized:
            return .granted
        case .denied:
            return hasPermissionBeenRequested(.notifications) ? .deniedPermanently : .denied
        case .notDetermined:
            return .notDetermined
        case .provisional:
            if #available(iOS 12.0, *) {
                return .limited
            } else {
                return .granted
            }
        case .ephemeral:
            if #available(iOS 14.0, *) {
                return .limited
            } else {
                return .granted
            }
        @unknown default:
            return .notDetermined
        }
    }

    /**
     * Request detailed permissions with options
     */
    @objc public func requestPermissions(options: [String: Any]?) async -> [String: Any] {
        // Check current status
        let currentStatus = await checkPermissions()
        let alreadyGranted = currentStatus["granted"] as? Bool ?? false

        if alreadyGranted {
            return currentStatus
        }

        // Handle rationale if provided
        if let showRationale = options?["showRationale"] as? Bool, showRationale {
            if let rationaleMessage = options?["rationaleMessage"] as? String {
                // On iOS, we can't show custom rationale before system dialog
                // but we could log it or handle it in app-specific way
                print("Rationale message: \(rationaleMessage)")
            }
        }

        // Start permission request sequence
        return await startPermissionRequestSequence()
    }

    /**
     * Start the sequential permission request process
     */
    private func startPermissionRequestSequence() async -> [String: Any] {
        // First check and request microphone permission
        let micStatus = await getMicrophonePermissionStatus()

        if micStatus != .granted {
            if micStatus == .notDetermined {
                // Request microphone permission
                markPermissionAsRequested(.microphone)
                let granted = await requestMicrophonePermission()

                if !granted {
                    // Permission denied, return current status
                    return await checkPermissions()
                }
            } else {
                // Permission already denied or restricted
                return await checkPermissions()
            }
        }

        // Microphone granted or already available, check notifications if needed
        if #available(iOS 10.0, *) {
            let notificationStatus = await getNotificationPermissionStatus()

            if notificationStatus != .granted && notificationStatus != .unsupported {
                if notificationStatus == .notDetermined {
                    // Request notification permission
                    markPermissionAsRequested(.notifications)
                    let _ = await requestNotificationPermission()
                }
            }
        }

        // Return final status
        return await checkPermissions()
    }

    /**
     * Request microphone permission
     */
    private func requestMicrophonePermission() async -> Bool {
        return await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
    }

    /**
     * Request notification permission
     */
    @available(iOS 10.0, *)
    private func requestNotificationPermission() async -> Bool {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            return granted
        } catch {
            print("Error requesting notification permission: \(error)")
            return false
        }
    }

    /**
     * Legacy permission check for backward compatibility
     */
    @objc public func checkLegacyPermissions() async -> [String: Any] {
        let audioStatus = AVAudioSession.sharedInstance().recordPermission
        let audioGranted = audioStatus == .granted

        // Check notification permission asynchronously
        let notificationGranted: Bool
        if #available(iOS 10.0, *) {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            notificationGranted = settings.authorizationStatus == .authorized
        } else {
            notificationGranted = true
        }

        return [
            "granted": audioGranted && notificationGranted,
            "audioPermission": audioGranted,
            "notificationPermission": notificationGranted
        ]
    }

    /**
     * Legacy permission request for backward compatibility
     */
    @objc public func requestLegacyPermissions() async -> [String: Any] {
        // First request audio permission
        let audioGranted = await requestMicrophonePermission()

        // Then check/request notification permission on iOS 10+
        var notificationGranted = true
        if #available(iOS 10.0, *) {
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            if settings.authorizationStatus == .notDetermined {
                // Request notification permission
                notificationGranted = await requestNotificationPermission()
            } else {
                notificationGranted = settings.authorizationStatus == .authorized
            }
        }

        return [
            "granted": audioGranted && notificationGranted,
            "audioPermission": audioGranted,
            "notificationPermission": notificationGranted
        ]
    }

    /**
     * Open app settings for manual permission management
     */
    @objc public func openAppSettings() {
        guard let settingsUrl = URL(string: UIApplication.openSettingsURLString) else {
            return
        }

        if UIApplication.shared.canOpenURL(settingsUrl) {
            UIApplication.shared.open(settingsUrl)
        }
    }

    /**
     * Validate that recording permissions are granted
     */
    @objc public func validateRecordingPermissions() throws {
        let audioSession = AVAudioSession.sharedInstance()
        guard audioSession.recordPermission == .granted else {
            throw NSError(domain: "PermissionError", code: 1001, userInfo: [
                NSLocalizedDescriptionKey: "Microphone permission is required for recording"
            ])
        }

        // We don't strictly require notification permission for recording,
        // but it's recommended for background recording
    }

    /**
     * Check if we can still prompt for permissions
     * This helps determine if permissions were permanently denied
     */
    private func canPromptForPermissions() -> Bool {
        let microphoneStatus = AVAudioSession.sharedInstance().recordPermission

        // If microphone is undetermined, we can prompt
        if microphoneStatus == .undetermined {
            return true
        }

        // If microphone is denied but we never requested it before,
        // it might be a first-time denial and we could try again
        if microphoneStatus == .denied && !hasPermissionBeenRequested(.microphone) {
            return true
        }

        // Check notifications if available
        if #available(iOS 10.0, *) {
            // We can check notification status asynchronously if needed
            // For now, assume we can prompt if not permanently denied
            return true
        }

        return false
    }

    // MARK: - Permission Tracking

    /**
     * Track if a permission has been requested before
     * This helps distinguish between NOT_DETERMINED and DENIED_PERMANENTLY
     */
    private func markPermissionAsRequested(_ permissionType: PermissionType) {
        let key = "\(permissionType.stringValue)_requested"
        userDefaults.set(true, forKey: key)
    }

    /**
     * Check if a permission has been requested before
     */
    private func hasPermissionBeenRequested(_ permissionType: PermissionType) -> Bool {
        let key = "\(permissionType.stringValue)_requested"
        return userDefaults.bool(forKey: key)
    }
}