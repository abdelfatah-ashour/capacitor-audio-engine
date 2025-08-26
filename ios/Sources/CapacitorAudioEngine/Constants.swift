import Foundation
@preconcurrency import AVFoundation

internal struct AudioEngineConstants {
    // MARK: - Audio Configuration
    static let defaultSampleRate: Double = 22050.0  // Optimized for smaller file size
    static let defaultChannels = 1
    static let defaultBitrate = 64000  // Optimized for smaller files
    static let defaultFileExtension = ".m4a"
    static let mimeTypeM4A = "audio/m4a"
    static let bufferSize: AVAudioFrameCount = 512

    // MARK: - Timing Constants
    static let timerInterval: TimeInterval = 1.0
    static let crossfadeDuration: TimeInterval = 0.02
    static let timestampMultiplier = 1000.0
    static let durationRoundingFactor = 10.0

    // MARK: - Network Configuration
    static let networkTimeout: TimeInterval = 60.0
    static let resourceTimeout: TimeInterval = 120.0
    static let networkCheckTimeout: TimeInterval = 2.0

    // MARK: - Segment Rolling Constants
    static let segmentDuration: TimeInterval = 300.0  // 5 minutes per segment (improved performance)
    static let maxRetentionDuration: TimeInterval = 600.0  // 10 minutes total retention
    static let maxSegments = Int(maxRetentionDuration / segmentDuration)  // 2 segments max

    // MARK: - Performance Constants
    static let minValidFileSize: Int64 = 100  // Minimum file size to consider valid
    static let compressionThreshold: Int = 1024 * 1024  // 1MB - compress files larger than this
    static let maxMemoryUsage: Int = 10 * 1024 * 1024  // 10MB max memory usage
    static let base64ChunkSize: Int = 1024 * 1024  // 1MB chunks for streaming
    static let compressionBufferSize: Int = 8192  // 8KB compression buffer

    // MARK: - Performance Thresholds
    static let maxProcessingTimeSeconds: TimeInterval = 5.0  // Max time for stop recording
    static let uiBlockingThresholdMs: TimeInterval = 16.0  // 60fps threshold
    static let maxCompressionAttempts: Int = 3  // Max retry attempts for compression

    // MARK: - Error Domains
    struct ErrorDomains {
        static let audioEngine = "AudioEngine"
        static let compression = "CompressionError"
        static let segmentRolling = "SegmentRollingManager"
        static let recording = "RecordingManager"
        static let playback = "PlaybackManager"
    }

    // MARK: - Quality Presets
    struct QualityPresets {
        struct Low {
            static let sampleRate: Double = 16000
            static let bitrate = 32000
            static let channels = 1
        }

        struct Medium {
            static let sampleRate: Double = 22050
            static let bitrate = 64000
            static let channels = 1
        }

        struct High {
            static let sampleRate: Double = 44100
            static let bitrate = 128000
            static let channels = 1
        }
    }
}