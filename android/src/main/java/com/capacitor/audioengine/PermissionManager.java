package com.capacitor.audioengine;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import android.Manifest;
import android.util.Log;

/**
 * Manages permission requests and checks for the audio engine
 */
public class PermissionManager {
    private static final String TAG = "PermissionManager";

    private final Context context;
    private final PermissionRequestCallback permissionCallback;

    /**
     * Interface for permission request callbacks
     */
    public interface PermissionRequestCallback {
        void requestPermission(String alias, PluginCall call, String callbackMethod);
    }

    public PermissionManager(Context context, PermissionRequestCallback permissionCallback) {
        this.context = context;
        this.permissionCallback = permissionCallback;
    }

    /**
     * Check if all required permissions are granted
     */
    public JSObject checkPermissions() {
        boolean audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        }

        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);

        Log.d(TAG, "Permission check - Audio: " + audioGranted + ", Notifications: " + notificationGranted);
        return result;
    }

    /**
     * Check if microphone permission is granted
     */
    public boolean hasMicrophonePermission() {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Check if notification permission is granted
     */
    public boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Not required for older Android versions
    }

    /**
     * Request permissions with callback handling
     */
    public void requestPermissions(PluginCall call) {
        Log.d(TAG, "requestPermissions called");

        boolean audioGranted = hasMicrophonePermission();
        boolean notificationGranted = hasNotificationPermission();

        Log.d(TAG, "Current permissions - Audio: " + audioGranted + ", Notifications: " + notificationGranted);

        if (audioGranted && notificationGranted) {
            Log.d(TAG, "All permissions already granted");
            JSObject result = new JSObject();
            result.put("granted", true);
            result.put("audioPermission", true);
            result.put("notificationPermission", true);
            call.resolve(result);
        } else if (!audioGranted) {
            // Request audio permission first - this will show the system dialog
            // The call will be resolved in the permission callback after user response
            Log.d(TAG, "Requesting RECORD_AUDIO permission");
            permissionCallback.requestPermission("microphone", call, "permissionCallback");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            // Request notification permission - this will show the system dialog
            // The call will be resolved in the permission callback after user response
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission");
            permissionCallback.requestPermission("notifications", call, "permissionCallback");
        } else {
            // Edge case: handle unexpected state
            Log.d(TAG, "Unexpected permission state, returning current status");
            JSObject result = new JSObject();
            result.put("granted", audioGranted && notificationGranted);
            result.put("audioPermission", audioGranted);
            result.put("notificationPermission", notificationGranted);
            call.resolve(result);
        }
    }

    /**
     * Handle permission callback
     */
    public void handlePermissionCallback(PluginCall call) {
        Log.d(TAG, "handlePermissionCallback called");

        boolean audioGranted = hasMicrophonePermission();
        boolean notificationGranted = hasNotificationPermission();

        Log.d(TAG, "Permission callback - Audio: " + audioGranted + ", Notifications: " + notificationGranted);

        // If audio permission was just granted but notification permission is still needed
        if (audioGranted && !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Audio granted, now requesting notification permission");
            permissionCallback.requestPermission("notifications", call, "permissionCallback");
            return;
        }

        // Return the final result
        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);
        call.resolve(result);
    }

    /**
     * Validate recording permissions before starting
     */
    public void validateRecordingPermissions() throws SecurityException {
        if (!hasMicrophonePermission()) {
            throw new SecurityException(AudioEngineError.MICROPHONE_PERMISSION_DENIED.getMessage());
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            throw new SecurityException(AudioEngineError.NOTIFICATION_PERMISSION_DENIED.getMessage());
        }
    }
}
