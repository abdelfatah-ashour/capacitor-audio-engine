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
}
