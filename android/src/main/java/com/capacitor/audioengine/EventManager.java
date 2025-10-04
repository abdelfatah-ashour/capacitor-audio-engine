package com.capacitor.audioengine;

import com.getcapacitor.JSObject;

/**
 * Manages event emissions for the audio engine
 */
public class EventManager implements WaveLevelEmitter.EventCallback {
    private static final String TAG = "EventManager";

    private final EventCallback eventCallback;

    /**
     * Interface for event emission callbacks
     */
    public interface EventCallback {
        void notifyListeners(String eventName, JSObject data);
    }

    public EventManager(EventCallback eventCallback) {
        this.eventCallback = eventCallback;
    }

    /**
     * Implementation of WaveLevelEmitter.EventCallback interface
     * Delegates to the internal event callback
     */
    @Override
    public void notifyListeners(String eventName, JSObject data) {
        eventCallback.notifyListeners(eventName, data);
    }
}
