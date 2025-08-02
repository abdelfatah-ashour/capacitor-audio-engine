package com.capacitor.audioengine;

/**
 * Simple interface for service binding to avoid direct class dependencies
 */
public interface ServiceBinder {
    /**
     * Get the service instance from the binder
     * @return The service instance that implements RecordingService interface
     */
    RecordingService getService();
}