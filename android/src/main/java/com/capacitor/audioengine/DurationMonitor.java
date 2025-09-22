package com.capacitor.audioengine;

import android.os.Handler;
import android.util.Log;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages duration monitoring for recordings
 */
public class DurationMonitor {
    private static final String TAG = "DurationMonitor";

    public interface DurationCallback {
        void onDurationChanged(double duration);
        void onMaxDurationReached();
    }

    private final Handler mainHandler;
    private final DurationCallback callback;

    private Timer durationTimer;
    private volatile double currentDuration = 0.0;
    private volatile boolean isMonitoring = false;
    private volatile boolean isPaused = false; // Track if duration monitoring is paused
    private volatile Integer maxDurationSeconds; // Maximum duration in seconds

    public DurationMonitor(Handler mainHandler, DurationCallback callback) {
        this.mainHandler = mainHandler;
        this.callback = callback;
    }

    /**
     * Start monitoring duration
     */
    public void startMonitoring() {
        stopMonitoring();
        Log.d(TAG, "Starting duration monitoring");

        isMonitoring = true;
        scheduleTimer();

        Log.d(TAG, "Duration timer started successfully");
    }

    private void scheduleTimer() {
        durationTimer = new Timer();
        durationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isMonitoring) {
                    Log.d(TAG, "Timer fired but monitoring is not active, skipping");
                    return;
                }

                // Only increment duration if not paused (i.e., actually recording)
                if (isPaused) {
                    Log.d(TAG, "Timer fired but duration monitoring is paused, skipping increment");
                    return;
                }

                currentDuration += 1.0;
                Log.d(TAG, "Duration incremented: " + currentDuration + " seconds");

                // Check if maximum duration has been reached
                if (maxDurationSeconds != null && currentDuration >= maxDurationSeconds) {
                    Log.d(TAG, "Maximum recording duration reached (" + maxDurationSeconds + " seconds), stopping recording");
                    stopMonitoring(); // Stop the timer immediately
                    mainHandler.post(callback::onMaxDurationReached);
                    return;
                }

                mainHandler.post(() -> {
                    Log.d(TAG, "Emitting duration change event with duration: " + currentDuration);
                    callback.onDurationChanged(currentDuration);
                });
            }
        }, 1000, 1000);
    }

    /**
     * Stop monitoring duration
     */
    public void stopMonitoring() {
        if (durationTimer != null) {
            Log.d(TAG, "Stopping duration monitoring");
            durationTimer.cancel();
            durationTimer = null;
        }
        isMonitoring = false;
    }

    /**
     * Pause duration monitoring (timer continues but duration doesn't increment)
     * Used when recording is paused (e.g., screen lock, interruptions)
     */
    public void pauseDuration() {
        if (!isPaused) {
            isPaused = true;
            // Cancel timer to ensure no increments happen while paused regardless of thread visibility
            if (durationTimer != null) {
                durationTimer.cancel();
                durationTimer = null;
            }
            Log.d(TAG, "Duration monitoring paused at " + currentDuration + " seconds (timer cancelled)");
        }
    }

    /**
     * Resume duration monitoring (duration starts incrementing again)
     * Used when recording is resumed after being paused
     */
    public void resumeDuration() {
        if (isPaused) {
            isPaused = false;
            // Recreate timer if monitoring is still active
            if (isMonitoring && durationTimer == null) {
                scheduleTimer();
            }
            Log.d(TAG, "Duration monitoring resumed at " + currentDuration + " seconds (timer restarted)");
        }
    }

    /**
     * Check if duration monitoring is currently paused
     */
    public boolean isPaused() {
        return isPaused;
    }

    /**
     * Reset duration to zero
     */
    public void resetDuration() {
        currentDuration = 0.0;
        isPaused = false; // Reset pause state as well
        Log.d(TAG, "Duration reset to 0");

        // Emit the reset duration immediately
        if (callback != null) {
            mainHandler.post(() -> callback.onDurationChanged(currentDuration));
        }
    }

    /**
     * Get current duration
     */
    public double getCurrentDuration() {
        return currentDuration;
    }

    /**
     * Set current duration (useful when resuming)
     */
    public void setCurrentDuration(double duration) {
        this.currentDuration = duration;
        Log.d(TAG, "Duration set to: " + duration + " seconds");
    }

    /**
     * Check if currently monitoring
     */
    public boolean isMonitoring() {
        return isMonitoring;
    }

    /**
     * Set maximum recording duration in seconds
     */
    public void setMaxDuration(Integer maxDurationSeconds) {
        this.maxDurationSeconds = maxDurationSeconds;
        Log.d(TAG, "Set max duration to: " + maxDurationSeconds + " seconds");
    }
}
