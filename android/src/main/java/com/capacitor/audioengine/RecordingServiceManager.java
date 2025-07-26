package com.capacitor.audioengine;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Manages the background recording service
 */
public class RecordingServiceManager {
    private static final String TAG = "RecordingServiceManager";

    private final Context context;
    private boolean serviceStarted = false;

    public RecordingServiceManager(Context context) {
        this.context = context;
    }

    /**
     * Start the background recording service
     */
    public void startService() {
        if (serviceStarted) {
            Log.d(TAG, "Recording service already started");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_START_RECORDING);

          context.startForegroundService(serviceIntent);

          serviceStarted = true;
            Log.d(TAG, "Recording service started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording service", e);
        }
    }

    /**
     * Stop the background recording service
     */
    public void stopService() {
        if (!serviceStarted) {
            Log.d(TAG, "Recording service not started");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_STOP_RECORDING);
            context.startService(serviceIntent);

            serviceStarted = false;
            Log.d(TAG, "Recording service stopped");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording service", e);
        }
    }

    /**
     * Pause the recording service notification
     */
    public void pauseService() {
        if (!serviceStarted) {
            Log.d(TAG, "Recording service not started, cannot pause");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_PAUSE_RECORDING);
            context.startService(serviceIntent);

            Log.d(TAG, "Recording service paused");
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause recording service", e);
        }
    }

    /**
     * Resume the recording service notification
     */
    public void resumeService() {
        if (!serviceStarted) {
            Log.d(TAG, "Recording service not started, cannot resume");
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_RESUME_RECORDING);
            context.startService(serviceIntent);

            Log.d(TAG, "Recording service resumed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume recording service", e);
        }
    }

    /**
     * Check if service is started
     */
    public boolean isServiceStarted() {
        return serviceStarted;
    }
}
