package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

/**
 * Standalone Permission Manager Service for handling audio recording permissions
 * with detailed status information and granular control.
 *
 * Provides comprehensive permission status mapping for Android:
 * - GRANTED: Permission is granted
 * - DENIED: Permission was denied but can be requested again
 * - DENIED_PERMANENTLY: Permission was denied with "Don't ask again"
 * - NOT_DETERMINED: Permission has never been requested
 * - UNSUPPORTED: Permission not available on current Android version
 */
public class PermissionManagerService {
    private static final String TAG = "PermissionManagerService";

    private final Context context;
    private final PermissionServiceCallback callback;

    /**
     * Interface for permission service callbacks
     */
    public interface PermissionServiceCallback {
        void requestPermission(String alias, PluginCall call, String callbackMethod);
        boolean shouldShowRequestPermissionRationale(String permission);
    }

    /**
     * Enum for detailed permission status
     */
    public enum PermissionStatus {
        GRANTED("granted"),
        DENIED("denied"),
        DENIED_PERMANENTLY("denied_permanently"),
        NOT_DETERMINED("not_determined"),
        RESTRICTED("restricted"),
        UNSUPPORTED("unsupported");

        private final String value;

        PermissionStatus(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    /**
     * Enum for permission types
     */
    public enum PermissionType {
        MICROPHONE("microphone", Manifest.permission.RECORD_AUDIO),
        NOTIFICATIONS("notifications", Manifest.permission.POST_NOTIFICATIONS);

        private final String name;
        private final String permission;

        PermissionType(String name, String permission) {
            this.name = name;
            this.permission = permission;
        }

        public String getName() {
            return name;
        }

        public String getPermission() {
            return permission;
        }
    }

    public PermissionManagerService(Context context, PermissionServiceCallback callback) {
        this.context = context;
        this.callback = callback;
    }

    /**
     * Check simplified permission status for all audio-related permissions
     */
    public JSObject checkPermissions() {
        Log.d(TAG, "Checking simplified permissions");

        // Check microphone permission
        PermissionStatus microphoneStatus = getDetailedPermissionStatus(PermissionType.MICROPHONE);

        // Check notification permission
        PermissionStatus notificationStatus = getDetailedPermissionStatus(PermissionType.NOTIFICATIONS);

        // Determine overall granted status
        boolean microphoneGranted = microphoneStatus == PermissionStatus.GRANTED;
        boolean notificationGranted = notificationStatus == PermissionStatus.GRANTED ||
                                     notificationStatus == PermissionStatus.UNSUPPORTED;
        boolean overallGranted = microphoneGranted && notificationGranted;

        // Determine overall status - prioritize the most restrictive status
        PermissionStatus overallStatus;
        if (overallGranted) {
            overallStatus = PermissionStatus.GRANTED;
        } else if (microphoneStatus == PermissionStatus.DENIED_PERMANENTLY ||
                   notificationStatus == PermissionStatus.DENIED_PERMANENTLY) {
            overallStatus = PermissionStatus.DENIED_PERMANENTLY;
        } else if (microphoneStatus == PermissionStatus.DENIED ||
                   notificationStatus == PermissionStatus.DENIED) {
            overallStatus = PermissionStatus.DENIED;
        } else if (microphoneStatus == PermissionStatus.NOT_DETERMINED ||
                   notificationStatus == PermissionStatus.NOT_DETERMINED) {
            overallStatus = PermissionStatus.NOT_DETERMINED;
        } else {
            overallStatus = PermissionStatus.DENIED;
        }

        JSObject result = new JSObject();
        result.put("granted", overallGranted);
        result.put("status", overallStatus.getValue());

        Log.d(TAG, "Simplified permission check completed - Overall granted: " + overallGranted +
                   ", Status: " + overallStatus.getValue());
        return result;
    }

    /**
     * Check microphone permission status with simplified information
     */
    public JSObject checkPermissionMicrophone() {
        Log.d(TAG, "Checking microphone permission");
        PermissionStatus status = getDetailedPermissionStatus(PermissionType.MICROPHONE);
        JSObject result = new JSObject();
        result.put("permissionType", PermissionType.MICROPHONE.getName());
        result.put("status", status.getValue());
        return result;
    }

    /**
     * Check notification permission status with simplified information
     */
    public JSObject checkPermissionNotifications() {
        Log.d(TAG, "Checking notification permission");
        PermissionStatus status = getDetailedPermissionStatus(PermissionType.NOTIFICATIONS);
        JSObject result = new JSObject();
        result.put("permissionType", PermissionType.NOTIFICATIONS.getName());
        result.put("status", status.getValue());
        return result;
    }

    /**
     * Check status for a single permission type
     */
    private JSObject checkSinglePermissionStatus(PermissionType permissionType) {
        JSObject result = new JSObject();
        result.put("permissionType", permissionType.getName());

        PermissionStatus status = getDetailedPermissionStatus(permissionType);
        result.put("status", status.getValue());

        // Add additional information
        switch (status) {
            case GRANTED:
                result.put("message", "Permission is granted");
                result.put("canRequestAgain", false);
                break;
            case DENIED:
                result.put("message", "Permission was denied but can be requested again");
                result.put("canRequestAgain", true);
                break;
            case DENIED_PERMANENTLY:
                result.put("message", "Permission was permanently denied. Please enable in Settings.");
                result.put("canRequestAgain", false);
                break;
            case NOT_DETERMINED:
                result.put("message", "Permission has not been requested yet");
                result.put("canRequestAgain", true);
                break;
            case UNSUPPORTED:
                result.put("message", "Permission not required on this Android version");
                result.put("canRequestAgain", false);
                break;
            case RESTRICTED:
                result.put("message", "Permission restricted by device policy");
                result.put("canRequestAgain", false);
                break;
        }

        return result;
    }

    /**
     * Get detailed permission status for a specific permission type
     */
    private PermissionStatus getDetailedPermissionStatus(PermissionType permissionType) {
        String permission = permissionType.getPermission();

        // Handle version-specific permissions
        if (permissionType == PermissionType.NOTIFICATIONS && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return PermissionStatus.UNSUPPORTED;
        }

        int permissionCheck = ContextCompat.checkSelfPermission(context, permission);

        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
            return PermissionStatus.GRANTED;
        }

        // Permission is not granted, determine why
        if (callback.shouldShowRequestPermissionRationale(permission)) {
            // User denied but can still be asked
            return PermissionStatus.DENIED;
        } else {
            // Either never requested or permanently denied
            // We need to track this via shared preferences or assume not determined for first time
            return hasPermissionBeenRequested(permissionType) ?
                   PermissionStatus.DENIED_PERMANENTLY :
                   PermissionStatus.NOT_DETERMINED;
        }
    }

    /**
     * Request detailed permissions with options
     */
    public void requestPermissions(PluginCall call, JSObject options) {
        Log.d(TAG, "Requesting detailed permissions");

        // Check current status
        JSObject currentStatus = checkPermissions();
    boolean alreadyGranted = currentStatus.getBoolean("granted", false);

        if (alreadyGranted) {
            Log.d(TAG, "All permissions already granted");
            call.resolve(currentStatus);
            return;
        }

        // Check if we should show rationale
    boolean showRationale = options != null && options.getBoolean("showRationale", false);
        if (showRationale) {
            String rationaleMessage = options != null ? options.getString("rationaleMessage") : null;
            if (rationaleMessage != null) {
                // Here you could show a dialog with the rationale message
                Log.d(TAG, "Rationale message: " + rationaleMessage);
            }
        }

        // Start permission request sequence
        startPermissionRequestSequence(call);
    }

    /**
     * Start the sequential permission request process
     */
    private void startPermissionRequestSequence(PluginCall call) {
        // First check microphone permission
        PermissionStatus micStatus = getDetailedPermissionStatus(PermissionType.MICROPHONE);

        if (micStatus != PermissionStatus.GRANTED) {
            if (micStatus == PermissionStatus.DENIED_PERMANENTLY) {
                // Direct user to settings
                Log.d(TAG, "Microphone permission permanently denied, directing to settings");
                JSObject result = checkPermissions();
                call.resolve(result);
                return;
            }

            // Request microphone permission
            Log.d(TAG, "Requesting microphone permission");
            markPermissionAsRequested(PermissionType.MICROPHONE);
            callback.requestPermission("microphone", call, "detailedPermissionCallback");
            return;
        }

        // Microphone granted, check notifications if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStatus notificationStatus = getDetailedPermissionStatus(PermissionType.NOTIFICATIONS);

            if (notificationStatus != PermissionStatus.GRANTED) {
                if (notificationStatus == PermissionStatus.DENIED_PERMANENTLY) {
                    Log.d(TAG, "Notification permission permanently denied");
                    JSObject result = checkPermissions();
                    call.resolve(result);
                    return;
                }

                // Request notification permission
                Log.d(TAG, "Requesting notification permission");
                markPermissionAsRequested(PermissionType.NOTIFICATIONS);
                callback.requestPermission("notifications", call, "detailedPermissionCallback");
                return;
            }
        }

        // All permissions granted
        Log.d(TAG, "All required permissions granted");
        JSObject result = checkPermissions();
        call.resolve(result);
    }

    /**
     * Handle detailed permission callback
     */
    public void handleDetailedPermissionCallback(PluginCall call) {
        Log.d(TAG, "Handling detailed permission callback");

        // Check current status after permission response
        JSObject currentStatus = checkPermissions();
        try {
            // Continue with next permission if needed
            boolean microphoneGranted = "granted".equals(
                ((JSObject) currentStatus.get("microphone")).getString("status")
            );

            if (microphoneGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                boolean notificationGranted = "granted".equals(
                    ((JSObject) currentStatus.get("notifications")).getString("status")
                );

                if (!notificationGranted) {
                    PermissionStatus notificationStatus = getDetailedPermissionStatus(PermissionType.NOTIFICATIONS);
                    if (notificationStatus == PermissionStatus.DENIED) {
                        // Try to request notification permission
                        Log.d(TAG, "Requesting notification permission after microphone granted");
                        callback.requestPermission("notifications", call, "detailedPermissionCallback");
                        return;
                    }
                }
            }
        } catch (org.json.JSONException e) {
            Log.e(TAG, "JSON error while checking permission status", e);
        }

        // Final result
        call.resolve(currentStatus);
    }

    /**
     * Legacy permission check for backward compatibility
     */
    public JSObject checkLegacyPermissions() {
        boolean audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                              == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                 == PackageManager.PERMISSION_GRANTED;
        }

        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);

        return result;
    }

    /**
     * Legacy permission request for backward compatibility
     */
    public void requestLegacyPermissions(PluginCall call) {
        boolean audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                              == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                 == PackageManager.PERMISSION_GRANTED;
        }

        if (audioGranted && notificationGranted) {
            JSObject result = new JSObject();
            result.put("granted", true);
            result.put("audioPermission", true);
            result.put("notificationPermission", true);
            call.resolve(result);
        } else if (!audioGranted) {
            markPermissionAsRequested(PermissionType.MICROPHONE);
            callback.requestPermission("microphone", call, "legacyPermissionCallback");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
            markPermissionAsRequested(PermissionType.NOTIFICATIONS);
            callback.requestPermission("notifications", call, "legacyPermissionCallback");
        }
    }

    /**
     * Handle legacy permission callback
     */
    public void handleLegacyPermissionCallback(PluginCall call) {
        boolean audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                              == PackageManager.PERMISSION_GRANTED;
        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                 == PackageManager.PERMISSION_GRANTED;
        }

        // If audio granted but notification needed
        if (audioGranted && !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callback.requestPermission("notifications", call, "legacyPermissionCallback");
            return;
        }

        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);
        call.resolve(result);
    }

    /**
     * Open app settings for manual permission management
     */
    public void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", context.getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /**
     * Validate that recording permissions are granted
     */
    public void validateRecordingPermissions() throws SecurityException {
        PermissionStatus micStatus = getDetailedPermissionStatus(PermissionType.MICROPHONE);
        if (micStatus != PermissionStatus.GRANTED) {
            throw new SecurityException("Microphone permission is required for recording");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStatus notificationStatus = getDetailedPermissionStatus(PermissionType.NOTIFICATIONS);
            if (notificationStatus == PermissionStatus.DENIED ||
                notificationStatus == PermissionStatus.DENIED_PERMANENTLY) {
                throw new SecurityException("Notification permission is required for background recording on Android 13+");
            }
        }
    }

    /**
     * Track if a permission has been requested before
     * This helps distinguish between NOT_DETERMINED and DENIED_PERMANENTLY
     */
    private void markPermissionAsRequested(PermissionType permissionType) {
        context.getSharedPreferences("audio_permissions", Context.MODE_PRIVATE)
               .edit()
               .putBoolean(permissionType.getName() + "_requested", true)
               .apply();
    }

    /**
     * Check if a permission has been requested before
     */
    private boolean hasPermissionBeenRequested(PermissionType permissionType) {
        return context.getSharedPreferences("audio_permissions", Context.MODE_PRIVATE)
                      .getBoolean(permissionType.getName() + "_requested", false);
    }
}