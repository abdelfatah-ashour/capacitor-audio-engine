package com.capacitor.audioengine;

import android.util.Log;
import com.getcapacitor.JSObject;

import java.util.Iterator;

/**
 * Manages event emissions for the audio engine
 */
public class EventManager implements WaveformDataManager.EventCallback {
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
     * Emit duration change event
     */
    public void emitDurationChange(double duration) {
        Log.d(TAG, "Emitting durationChange event with duration: " + duration);

        JSObject data = new JSObject();
        data.put("duration", duration);

        eventCallback.notifyListeners("durationChange", data);
    }

    /**
     * Emit error event
     */
    public void emitError(JSObject errorData) {
        Log.e(TAG, "Emitting error event: " + errorData.toString());
        eventCallback.notifyListeners("error", errorData);
    }

    /**
     * Emit recording state change event
     */
    public void emitRecordingStateChange(String state, JSObject additionalData) {
        Log.d(TAG, "Emitting recording state change: " + state);

        JSObject data = new JSObject();
        data.put("state", state);
        data.put("timestamp", System.currentTimeMillis());

        // Merge additional data if provided
        if (additionalData != null) {
          for (Iterator<String> it = additionalData.keys(); it.hasNext(); ) {
            String key = it.next();
            try {
                data.put(key, additionalData.get(key));
            } catch (Exception e) {
                Log.w(TAG, "Failed to add key " + key + " to state change event", e);
            }
          }
        }

        eventCallback.notifyListeners("recordingStateChange", data);
    }

    /**
     * Implementation of WaveformDataManager.EventCallback interface
     * Delegates to the internal event callback
     */
    @Override
    public void notifyListeners(String eventName, JSObject data) {
        eventCallback.notifyListeners(eventName, data);
    }
}
