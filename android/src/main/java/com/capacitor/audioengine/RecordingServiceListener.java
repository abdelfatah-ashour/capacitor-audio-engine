package com.capacitor.audioengine;

/**
 * Interface for listening to recording service events
 * Separated from AudioRecordingService to avoid circular dependencies
 */
public interface RecordingServiceListener {
    void onScreenLocked();
    void onScreenUnlocked();
    void onRecordingStateChanged(boolean isRecording);
}