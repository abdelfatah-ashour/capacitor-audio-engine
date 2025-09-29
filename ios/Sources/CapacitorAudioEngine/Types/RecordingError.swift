import Foundation

// MARK: - Recording Error Types
enum RecordingError: LocalizedError {
    case permissionDenied
    case deviceBusy(reason: String)
    case noActiveRecording
    case recordingFailed(underlying: Error)
    case compressionFailed(underlying: Error)
    case invalidConfiguration(parameter: String)
    case fileOperationFailed(operation: String, underlying: Error)
    case memoryPressure
    case processingTimeout
    case invalidDuration(value: Double)

    var errorDescription: String? {
        switch self {
        case .permissionDenied:
            return "Microphone permission is required for recording"
        case .deviceBusy(let reason):
            return "Recording device is busy: \(reason)"
        case .noActiveRecording:
            return "No active recording session found"
        case .recordingFailed(let error):
            return "Recording failed: \(error.localizedDescription)"
        case .compressionFailed(let error):
            return "Audio compression failed: \(error.localizedDescription)"
        case .invalidConfiguration(let parameter):
            return "Invalid recording configuration: \(parameter)"
        case .fileOperationFailed(let operation, let error):
            return "File \(operation) failed: \(error.localizedDescription)"
        case .memoryPressure:
            return "Insufficient memory available for recording operation"
        case .processingTimeout:
            return "Recording processing timed out"
        case .invalidDuration(let value):
            return "Invalid duration value: \(value)"
        }
    }

    var failureReason: String? {
        switch self {
        case .permissionDenied:
            return "The app needs microphone access to record audio"
        case .deviceBusy:
            return "Another app may be using the microphone"
        case .noActiveRecording:
            return "No recording is currently in progress"
        case .recordingFailed:
            return "An error occurred during the recording process"
        case .compressionFailed:
            return "Failed to compress the recorded audio"
        case .invalidConfiguration:
            return "The recording parameters are not valid"
        case .fileOperationFailed:
            return "Could not access or modify the recording file"
        case .memoryPressure:
            return "The device is low on memory"
        case .processingTimeout:
            return "The operation took too long to complete"
        case .invalidDuration:
            return "The specified duration is not valid"
        }
    }

    var recoverySuggestion: String? {
        switch self {
        case .permissionDenied:
            return "Grant microphone permission in Settings > Privacy & Security > Microphone"
        case .deviceBusy:
            return "Close other apps using the microphone and try again"
        case .noActiveRecording:
            return "Start a recording session before attempting to stop"
        case .recordingFailed, .compressionFailed, .fileOperationFailed:
            return "Try recording again. If the problem persists, restart the app"
        case .invalidConfiguration:
            return "Check the recording parameters and try again"
        case .memoryPressure:
            return "Close other apps to free up memory and try again"
        case .processingTimeout:
            return "Try again with a shorter recording or restart the app"
        case .invalidDuration:
            return "Use a positive duration value"
        }
    }

    var errorCode: Int {
        switch self {
        case .permissionDenied: return 1001
        case .deviceBusy: return 1002
        case .noActiveRecording: return 1003
        case .recordingFailed: return 1004
        case .compressionFailed: return 1005
        case .invalidConfiguration: return 1006
        case .fileOperationFailed: return 1007
        case .memoryPressure: return 1008
        case .processingTimeout: return 1009
        case .invalidDuration: return 1010
        }
    }
}

// MARK: - Playback Error Types
enum PlaybackError: LocalizedError {
    case emptyPlaylist
    case invalidTrackIndex(index: Int)
    case invalidTrackUrl(String)
    case loadingFailed(url: String, underlying: Error)
    case playbackFailed(String)
    case seekFailed(position: TimeInterval)
    case networkError(underlying: Error)
    case fileNotFound(path: String)
    case unsupportedFormat(format: String)

    var errorDescription: String? {
        switch self {
        case .emptyPlaylist:
            return "Cannot play from an empty playlist"
        case .invalidTrackIndex(let index):
            return "Track index \(index) is out of range"
        case .invalidTrackUrl(let message):
            return "Invalid track URL: \(message)"
        case .loadingFailed(let url, let error):
            return "Failed to load audio from \(url): \(error.localizedDescription)"
        case .playbackFailed(let message):
            return "Playback failed: \(message)"
        case .seekFailed(let position):
            return "Failed to seek to position \(position)"
        case .networkError(let error):
            return "Network error: \(error.localizedDescription)"
        case .fileNotFound(let path):
            return "Audio file not found: \(path)"
        case .unsupportedFormat(let format):
            return "Unsupported audio format: \(format)"
        }
    }

    var errorCode: Int {
        switch self {
        case .emptyPlaylist: return 2001
        case .invalidTrackIndex: return 2002
        case .invalidTrackUrl: return 2003
        case .loadingFailed: return 2004
        case .playbackFailed: return 2005
        case .seekFailed: return 2006
        case .networkError: return 2007
        case .fileNotFound: return 2008
        case .unsupportedFormat: return 2009
        }
    }
}

// MARK: - Helper Extensions
extension Error {
    /// Converts any error to a RecordingError with context
    func asRecordingError(context: String) -> RecordingError {
        if let recordingError = self as? RecordingError {
            return recordingError
        }
        return .recordingFailed(underlying: self)
    }

    /// Creates an NSError from RecordingError for Capacitor compatibility
    var asNSError: NSError {
        if let playbackError = self as? PlaybackError {
            return NSError(
                domain: AudioEngineConstants.ErrorDomains.playback,
                code: playbackError.errorCode,
                userInfo: [
                    NSLocalizedDescriptionKey: playbackError.localizedDescription
                ]
            )
        }

        // Generic error conversion
        return self as NSError
    }
}
