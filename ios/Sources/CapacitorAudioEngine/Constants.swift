import Foundation
@preconcurrency import AVFoundation

internal struct AudioEngineConstants {
    // MARK: - Audio Configuration
    // Defaults tuned for 44.1kHz mono AAC at 128 kbps per repo guidelines
    static let defaultSampleRate: Double = 48000.0
    static let defaultChannels = 1
    static let defaultBitrate = 128000
    static let defaultFileExtension = ".m4a"
    static let mimeTypeM4A = "audio/m4a"
    static let bufferSize: AVAudioFrameCount = 512 // tiny buffer to smooth transitions

    // MARK: - Timing Constants
    static let timerInterval: TimeInterval = 1.0
    static let crossfadeDuration: TimeInterval = 0.02
    static let timestampMultiplier = 1000.0
    static let durationRoundingFactor = 10.0

    // MARK: - Network Configuration
    static let networkTimeout: TimeInterval = 60.0
    static let resourceTimeout: TimeInterval = 120.0
    static let networkCheckTimeout: TimeInterval = 2.0

    // MARK: - Performance Constants
    static let minValidFileSize: Int64 = 100  // Minimum file size to consider valid
    static let compressionThreshold: Int = 1024 * 1024  // 1MB - compress files larger than this
    static let maxMemoryUsage: Int = 30 * 1024 * 1024  // 30MB max memory usage
    static let compressionBufferSize: Int = 8192  // 8KB compression buffer

    // MARK: - Performance Thresholds
    static let maxProcessingTimeSeconds: TimeInterval = 5.0  // Max time for stop recording
    static let uiBlockingThresholdMs: TimeInterval = 16.0  // 60fps threshold
    static let maxCompressionAttempts: Int = 3  // Max retry attempts for compression

    // MARK: - Error Domains
    struct ErrorDomains {
        static let audioEngine = "AudioEngine"
        static let compression = "CompressionError"
        static let playback = "PlaybackManager"
    }
}