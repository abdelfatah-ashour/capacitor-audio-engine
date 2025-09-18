package com.capacitor.audioengine;

/**
 * Interface for recording service operations
 * This interface decouples the plugin from the concrete service implementation
 */
public interface RecordingService {
    /**
     * Start foreground recording service
     */
    void startForegroundRecording();

    /**
     * Stop foreground recording service
     */
    void stopForegroundRecording();
}