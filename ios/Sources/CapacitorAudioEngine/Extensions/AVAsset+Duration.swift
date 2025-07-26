import Foundation
@preconcurrency import AVFoundation

// MARK: - AVAsset Extensions
extension AVAsset {
    /// Asynchronously loads the duration of the asset
    /// - Returns: Duration in seconds, or 0 if invalid
    func loadDurationAsync() async -> TimeInterval {
        // Use modern async API on iOS 15+ for better performance
        if #available(iOS 15.0, *) {
            do {
                let duration = try await load(.duration)
                return duration.seconds.isFinite ? duration.seconds : 0
            } catch {
                print("[AVAsset] Failed to load duration: \(error.localizedDescription)")
                return 0
            }
        } else {
            // Fallback for iOS 14 and below - capture duration before async operation
            // to avoid Sendable warnings with non-Sendable AVAsset
            let duration = self.duration
            return await withCheckedContinuation { continuation in
                DispatchQueue.global(qos: .userInitiated).async {
                    let seconds = duration.seconds.isFinite ? duration.seconds : 0
                    continuation.resume(returning: seconds)
                }
            }
        }
    }

    /// Safely gets duration in seconds
    /// - Returns: Duration in seconds, or 0 if invalid/unavailable
    var safeDurationSeconds: TimeInterval {
        let duration = self.duration
        return duration.seconds.isFinite ? duration.seconds : 0
    }
}
