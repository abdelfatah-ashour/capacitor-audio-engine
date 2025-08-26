package com.capacitor.audioengine;

/**
 * Interface for recording service operations
 * This interface decouples the plugin from the concrete service implementation
 */
public interface RecordingService {
    /**
     * Set the listener for recording service events
     * @param listener The listener to receive service events
     */
    void setRecordingServiceListener(RecordingServiceListener listener);

    /**
     * Start foreground recording service
     */
    void startForegroundRecording();

    /**
     * Stop foreground recording service
     */
    void stopForegroundRecording();
}