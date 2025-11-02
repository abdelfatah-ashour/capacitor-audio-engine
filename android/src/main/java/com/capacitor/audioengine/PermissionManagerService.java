package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

/**
 * Simplified permission management service for CapacitorAudioEngine plugin.
 * Handles microphone and notification permissions with streamlined methods.
 */
public class PermissionManagerService {
    private static final String TAG = "PermissionManagerService";

    private final Context context;
    private final PermissionServiceCallback callback;

    public interface PermissionServiceCallback {
        void requestPermission(String alias, PluginCall call, String callbackMethod);
        boolean shouldShowRequestPermissionRationale(String permission);
    }

    public PermissionManagerService(Context context, PermissionServiceCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Check all permissions status for audio recording
     * Returns comprehensive permission status including individual permissions
     */
    public JSObject checkPermissions() {
        Log.d(TAG, "Checking audio recording permissions");

        // Check microphone permission
        boolean micGranted = isPermissionGranted(Manifest.permission.RECORD_AUDIO);
        String micStatus = getPermissionStatusString(Manifest.permission.RECORD_AUDIO);

        // Check notification permission (Android 13+)
        boolean notifGranted = true; // Default to granted for older Android versions
        String notifStatus = "unsupported";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS);
            notifStatus = getPermissionStatusString(Manifest.permission.POST_NOTIFICATIONS);
        }

        // Build response
        JSObject result = new JSObject();
        result.put("granted", micGranted && notifGranted);
        result.put("status", micGranted && notifGranted ? "granted" : "denied");

        JSObject micResult = new JSObject();
        micResult.put("granted", micGranted);
        micResult.put("status", micStatus);
        result.put("microphone", micResult);

        JSObject notifResult = new JSObject();
        notifResult.put("granted", notifGranted);
        notifResult.put("status", notifStatus);
        result.put("notifications", notifResult);

        Log.d(TAG, "Permission check - Mic: " + micStatus + ", Notif: " + notifStatus +
                   ", Overall: " + (micGranted && notifGranted));

        return result;
    }

    /**
     * Request all required permissions for audio recording
     * Handles sequential permission requests with proper callbacks
     */
    public void requestPermissions(PluginCall call) {
        Log.d(TAG, "Requesting audio recording permissions");

        // Check if already granted
        JSObject currentStatus = checkPermissions();
        if (Boolean.TRUE.equals(currentStatus.getBoolean("granted", false))) {
            Log.d(TAG, "All permissions already granted");
            call.resolve(currentStatus);
            return;
        }

        // Start with microphone permission
        requestMicrophonePermission(call);
    }

    /**
     * Request microphone permission only
     */
    public void requestPermissionMicrophone(PluginCall call) {
        Log.d(TAG, "Requesting microphone permission");

        // Check if already granted
        JSObject currentStatus = checkPermissions();
        JSObject micResult = currentStatus.getJSObject("microphone");
        if (micResult != null && Boolean.TRUE.equals(micResult.getBoolean("granted", false))) {
            Log.d(TAG, "Microphone permission already granted");
            call.resolve(checkPermissionMicrophone());
            return;
        }

        // Request microphone permission
        if (isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            // Microphone already granted
            call.resolve(checkPermissionMicrophone());
            return;
        }

        Log.d(TAG, "Requesting microphone permission");
        try {
            callback.requestPermission("microphone", call, "permissionCallbackMicrophone");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting microphone permission", e);
            call.reject("Failed to request microphone permission: " + e.getMessage());
        }
    }

    /**
     * Request notification permission only
     */
    public void requestPermissionNotifications(PluginCall call) {
        Log.d(TAG, "Requesting notification permission");

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No notification permission needed on older Android versions
            Log.d(TAG, "Notification permission not required on Android < 13");
            call.resolve(checkPermissionNotifications());
            return;
        }

        // Check if already granted
        JSObject currentStatus = checkPermissions();
        JSObject notifResult = currentStatus.getJSObject("notifications");
        if (notifResult != null && Boolean.TRUE.equals(notifResult.getBoolean("granted", false))) {
            Log.d(TAG, "Notification permission already granted");
            call.resolve(checkPermissionNotifications());
            return;
        }

        // Request notification permission
        if (isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            // Notification already granted
            call.resolve(checkPermissionNotifications());
            return;
        }

        Log.d(TAG, "Requesting notification permission");
        try {
            callback.requestPermission("notifications", call, "permissionCallbackNotifications");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting notification permission", e);
            call.reject("Failed to request notification permission: " + e.getMessage());
        }
    }

    /**
     * Check microphone permission status only
     */
    public JSObject checkPermissionMicrophone() {
        boolean micGranted = isPermissionGranted(Manifest.permission.RECORD_AUDIO);
        String micStatus = getPermissionStatusString(Manifest.permission.RECORD_AUDIO);

        JSObject result = new JSObject();
        result.put("granted", micGranted);
        result.put("status", micStatus);

        return result;
    }

    /**
     * Check notification permission status only
     */
    public JSObject checkPermissionNotifications() {
        boolean notifGranted = true; // Default to granted for older Android versions
        String notifStatus = "unsupported";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS);
            notifStatus = getPermissionStatusString(Manifest.permission.POST_NOTIFICATIONS);
        }

        JSObject result = new JSObject();
        result.put("granted", notifGranted);
        result.put("status", notifStatus);

        return result;
    }

    /**
     * Open app settings for manual permission management
     */
    public void openSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", context.getPackageName(), null);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "Opened app settings");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open settings", e);
            throw new RuntimeException("Failed to open app settings: " + e.getMessage());
        }
    }

    /**
     * Validate recording permissions before starting recording
     * Throws SecurityException if permissions are not granted
     */
    public void validateRecordingPermissions() throws SecurityException {
        // Check microphone permission
        if (!isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            throw new SecurityException("Microphone permission required for recording");
        }

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                throw new SecurityException("Notification permission required for background recording on Android 13+");
            }
        }
    }

    /**
     * Handle permission callback and request next permission if needed
     * This method is called from the plugin's permissionCallback method
     */
    public void handlePermissionCallback(PluginCall call, boolean granted) {
        Log.d(TAG, "Handling permission callback - granted: " + granted);

        if (granted) {
            // Permission was granted, check if we need to request more permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Check if we still need notification permission
                if (!isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                    Log.d(TAG, "Microphone granted, requesting notification permission");
                    requestNotificationPermissionIfNeeded(call);
                    return;
                }
            }

            // All permissions granted
            Log.d(TAG, "All permissions granted");
            call.resolve(checkPermissions());
        } else {
            // Permission was denied, return current status
            Log.d(TAG, "Permission denied, returning current status");
            call.resolve(checkPermissions());
        }
    }

    /**
     * Handle microphone permission callback
     */
    public void handlePermissionCallbackMicrophone(PluginCall call, boolean granted) {
        Log.d(TAG, "Handling microphone permission callback - granted: " + granted);
        call.resolve(checkPermissionMicrophone());
    }

    /**
     * Handle notification permission callback
     */
    public void handlePermissionCallbackNotifications(PluginCall call, boolean granted) {
        Log.d(TAG, "Handling notification permission callback - granted: " + granted);
        call.resolve(checkPermissionNotifications());
    }

    // ========== Private Helper Methods ==========

    /**
     * Request microphone permission
     */
    private void requestMicrophonePermission(PluginCall call) {
        if (isPermissionGranted(Manifest.permission.RECORD_AUDIO)) {
            // Microphone already granted, check notifications
            requestNotificationPermissionIfNeeded(call);
            return;
        }

        Log.d(TAG, "Requesting microphone permission");
        try {
            callback.requestPermission("microphone", call, "permissionCallback");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting microphone permission", e);
            call.reject("Failed to request microphone permission: " + e.getMessage());
        }
    }

    /**
     * Request notification permission if needed (Android 13+)
     */
    private void requestNotificationPermissionIfNeeded(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No notification permission needed on older Android versions
            call.resolve(checkPermissions());
            return;
        }

        if (isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            // All permissions granted
            call.resolve(checkPermissions());
            return;
        }

        Log.d(TAG, "Requesting notification permission");
        try {
            callback.requestPermission("notifications", call, "permissionCallback");
        } catch (Exception e) {
            Log.e(TAG, "Error requesting notification permission", e);
            call.reject("Failed to request notification permission: " + e.getMessage());
        }
    }

    /**
     * Check if a specific permission is granted
     */
    private boolean isPermissionGranted(String permission) {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Get permission status string
     */
    private String getPermissionStatusString(String permission) {
        if (isPermissionGranted(permission)) {
            return "granted";
        }

        boolean shouldShowRationale = callback.shouldShowRequestPermissionRationale(permission);
        return shouldShowRationale ? "denied" : "denied_permanently";
    }
}