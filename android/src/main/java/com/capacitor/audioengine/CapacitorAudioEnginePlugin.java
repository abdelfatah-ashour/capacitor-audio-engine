package com.capacitor.audioengine;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.os.Looper;
import android.util.Log;


import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(
    name = "CapacitorAudioEngine",
    permissions = {
        @Permission(
            alias = "microphone",
            strings = { Manifest.permission.RECORD_AUDIO }
        ),
        @Permission(
            alias = "notifications",
            strings = { Manifest.permission.POST_NOTIFICATIONS }
        )
    }
)
public class CapacitorAudioEnginePlugin extends Plugin implements EventManager.EventCallback, PlaybackManager.PlaybackManagerListener, AudioInterruptionManager.InterruptionCallback {
    private static final String TAG = "CapacitorAudioEngine";

    // Core managers
    private PermissionManagerService permissionService; // Standalone permission service
    private EventManager eventManager;

    private FileDirectoryManager fileManager;
    private PlaybackManager playbackManager;

    // Wave level emitter for real-time audio levels
    private WaveLevelEmitter waveLevelEmitter;

    // Background recording service
    private RecordingService recordingService;
    private boolean isServiceBound = false;

    // Segment rolling support
    private SegmentRollingManager segmentRollingManager;
    private boolean isSegmentRollingEnabled = false;

    // Segment rolling configuration
    private Integer segmentLengthSeconds = 60; // Default 60 seconds (1 minute) per segment

    // Recording state
    private boolean isRecording = false;
    private AudioRecordingConfig recordingConfig;
    private Integer maxDurationSeconds; // Maximum recording duration in seconds

    // Thread management
    private Handler mainHandler;

    @Override
    public void load() {
        super.load();
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize managers
        permissionService = new PermissionManagerService(getContext(), new PermissionManagerService.PermissionServiceCallback() {
            public void requestPermission(String alias, PluginCall call, String callbackMethod) {
                requestPermissionForAlias(alias, call, callbackMethod);
            }

            public boolean shouldShowRequestPermissionRationale(String permission) {
                return getActivity() != null &&
                       ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), permission);
            }
        });
        eventManager = new EventManager(this);
        fileManager = new FileDirectoryManager(getContext());

        // Initialize wave level emitter with event manager callback
        waveLevelEmitter = new WaveLevelEmitter(eventManager);


        // Initialize recording configuration with defaults
        recordingConfig = new AudioRecordingConfig.Builder()
            .sampleRate(AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE)
            .channels(AudioEngineConfig.Recording.DEFAULT_CHANNELS)
            .bitrate(AudioEngineConfig.Recording.DEFAULT_BITRATE)
            .build();

        // Initialize playback manager
        playbackManager = new PlaybackManager(getContext(), this);

        // Initialize managers that depend on callbacks
        initializeCallbackManagers();

        Log.d(TAG, "CapacitorAudioEngine plugin loaded");
    }

    // Service connection for background recording service
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "AudioRecordingService connected");
            // Use interface to decouple from concrete service implementation
            if (service instanceof ServiceBinder binder) {
              recordingService = binder.getService();
                isServiceBound = true;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "AudioRecordingService disconnected");
            recordingService = null;
            isServiceBound = false;
        }
    };

    private void initializeCallbackManagers() {
        // Callback managers removed since segment rolling handles its own timing and duration
        Log.d(TAG, "Segment rolling manager will handle all timing and duration callbacks");
    }

    /**
     * Safely gets an integer value from PluginCall, handling potential null returns
     * to prevent NullPointerException during unboxing.
     *
     * @param call The PluginCall instance
     * @param key The parameter key to retrieve
     * @param defaultValue The default value to use if null or not present
     * @return The integer value or default if null
     */
    private int getIntegerSafely(PluginCall call, String key, int defaultValue) {
        Integer value = call.getInt(key);
        return value != null ? value : defaultValue;
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        JSObject result = permissionService.checkPermissions();
        call.resolve(result);
    }

    @PluginMethod
    public void checkPermissionMicrophone(PluginCall call) {
        JSObject result = permissionService.checkPermissionMicrophone();
        call.resolve(result);
    }

    @PluginMethod
    public void checkPermissionNotifications(PluginCall call) {
        JSObject result = permissionService.checkPermissionNotifications();
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        JSObject options = call.getObject("options", new JSObject());
        permissionService.requestPermissions(call, options);
    }

    @PermissionCallback
    private void detailedPermissionCallback(PluginCall call) {
        Log.d(TAG, "detailedPermissionCallback triggered - handling detailed permission response");
        // Delegate to the permission service for detailed permission handling
        permissionService.handleDetailedPermissionCallback(call);
    }

  // Implementation of PermissionRequestCallback interface
    // Not an override: PermissionServiceCallback is implemented as an anonymous inner class, not here.
    public void requestPermission(String alias, PluginCall call, String callbackMethod) {
        requestPermissionForAlias(alias, call, callbackMethod);
    }

    // Implementation of EventCallback interface
    @Override
    public void notifyListeners(String eventName, JSObject data) {
        super.notifyListeners(eventName, data);
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        try {
            startRecordingInternal(call);
        } catch (SecurityException e) {
            Log.e(TAG, "Security error in startRecording", e);
            call.reject(AudioEngineError.PERMISSION_DENIED.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Validation error in startRecording", e);
            call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(), e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            cleanupRecordingState();

            JSObject errorData = new JSObject();
            errorData.put("message", "Failed to start recording: " + e.getMessage());
            eventManager.emitError(errorData);
            call.reject(AudioEngineError.INITIALIZATION_FAILED.getCode(),
                       AudioEngineError.INITIALIZATION_FAILED.getDetailedMessage(e.getMessage()));
        }
    }

    /**
     * Internal method to handle recording start logic
     */
    private void startRecordingInternal(PluginCall call) throws Exception {
        Log.d(TAG, "startRecording called - current state: isRecording=" + isRecording);

        // Validate state - check if segment rolling is already active
        if (isRecording || (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive())) {
            call.reject(AudioEngineError.RECORDING_IN_PROGRESS.getCode(),
                       AudioEngineError.RECORDING_IN_PROGRESS.getMessage());
            return;
        }

        // Check permissions using the new permission service
        permissionService.validateRecordingPermissions();

        // Clean up any leftover state from previous recordings or interruptions
        cleanupRecordingState();

        // Get and validate recording options
        int sampleRate = getIntegerSafely(call, "sampleRate", AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE);
        int channels = getIntegerSafely(call, "channels", AudioEngineConfig.Recording.DEFAULT_CHANNELS);
        int bitrate = getIntegerSafely(call, "bitrate", AudioEngineConfig.Recording.DEFAULT_BITRATE);

        // Get audio format (optional, defaults to M4A)
        String formatString = call.getString("format", "m4a");
        AudioRecordingFormat audioFormat = AudioRecordingFormat.fromString(formatString);
        Log.d(TAG, "Using audio format: " + audioFormat + " (requested: " + formatString + ")");

        // Enhanced sample rate validation
        if (sampleRate < 8000 || sampleRate > 48000) {
            throw new IllegalArgumentException("Sample rate must be between 8000 and 48000 Hz, got: " + sampleRate);
        }

        // Validate maxDuration is provided and reasonable
        Integer maxDuration = call.getInt("maxDuration"); // Can be null
        Log.d(TAG, "Received maxDuration from call: " + maxDuration);

        if (maxDuration == null || maxDuration <= 0) {
            Log.e(TAG, "Invalid maxDuration: " + maxDuration + " - must be greater than 0 seconds");
            throw new IllegalArgumentException("maxDuration is required and must be greater than 0 seconds, got: " + maxDuration);
        }
        if (maxDuration > 7200) { // 2 hours max
            Log.e(TAG, "maxDuration too large: " + maxDuration + " seconds");
            throw new IllegalArgumentException("maxDuration cannot exceed 7200 seconds (2 hours), got: " + maxDuration);
        }

        Log.d(TAG, "Validated maxDuration: " + maxDuration + " seconds");

        // Get segment length configuration (optional, defaults to 10 seconds)
        Integer segmentLength = call.getInt("segmentLength");
        if (segmentLength != null) {
            if (segmentLength < 1 || segmentLength > 60) {
                throw new IllegalArgumentException("segmentLength must be between 1 and 60 seconds, got: " + segmentLength);
            }
            segmentLengthSeconds = segmentLength;
            Log.d(TAG, "Using custom segment length: " + segmentLengthSeconds + " seconds");
        } else {
            Log.d(TAG, "Using default segment length: " + segmentLengthSeconds + " seconds");
        }

        // Create recording configuration
        recordingConfig = new AudioRecordingConfig.Builder()
            .sampleRate(sampleRate)
            .channels(channels)
            .bitrate(bitrate)
            .build();

        Log.d(TAG, "Using config - SampleRate: " + sampleRate +
              ", Channels: " + channels + ", Bitrate: " + bitrate);

        // Store maxDuration for use in recording logic
        maxDurationSeconds = maxDuration;

        // Validate audio parameters
        ValidationUtils.validateAudioParameters(sampleRate, channels, bitrate);

        Log.d(TAG, "Starting recording with maxDuration: " + maxDuration + " seconds");

        // Always use segment rolling for recording
        isSegmentRollingEnabled = true;
        Log.d(TAG, "Using segment rolling mode for recording with format: " + audioFormat);
        try {
            startSegmentRollingRecording();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start segment rolling recording", e);
            cleanupRecordingState();
            throw e;
        }

        call.resolve();
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        pauseRecordingInternal(call);
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        resumeRecordingInternal(call);
    }

    @PluginMethod
    public void resetRecording(PluginCall call) {
        // Ensure there is an active recording session context to reset
        boolean sessionActive = isRecording ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (!sessionActive) {
            call.reject("No active recording session to reset");
            return;
        }

        try {
            // Discard current recording segments and reset duration inside manager
            if (segmentRollingManager != null) {
                segmentRollingManager.resetSegmentRolling();
                Log.d(TAG, "Segment rolling reset successfully");
            }

            // Discard waves by stopping wave level monitoring (frontend should clear visualization on waveLevelDestroy)
            if (waveLevelEmitter != null) {
                waveLevelEmitter.stopMonitoring();
                Log.d(TAG, "Wave level monitoring stopped (waves discarded)");
            }

            // Keep recordingConfig/maxDuration for resume; mark session as paused state
            if (eventManager != null) {
                JSObject pauseData = new JSObject();
                pauseData.put("duration", 0.0);
                pauseData.put("isRecording", true); // Session remains active and resumable
                pauseData.put("status", "paused");
                eventManager.emitRecordingStateChange("paused", pauseData);
            }

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to reset recording", e);
            call.reject("Failed to reset recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            JSObject result = stopRecordingInternal();
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            call.reject("Failed to stop recording: " + e.getMessage());
        }
    }

    /**
     * Internal method to stop recording without requiring a PluginCall
     * Used by both the public stopRecording method and the max duration callback
     */
    private JSObject stopRecordingInternal() throws Exception {
        // Check if any recording is active
        boolean hasActiveRecording = isRecording ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (!hasActiveRecording) {
            throw new IllegalStateException("No active recording to stop");
        }

        String fileToReturn;

        Log.d(TAG, "Stopping segment rolling recording...");
        // Stop segment rolling and merge segments
        if (segmentRollingManager != null) {
            File mergedFile = segmentRollingManager.stopSegmentRolling();

            if (mergedFile == null) {
                Log.e(TAG, "Segment rolling returned null - no valid segments found");
                throw new IllegalStateException("No valid audio segments found to return");
            }

            fileToReturn = mergedFile.getAbsolutePath();

            // Stop recording service as recording is complete
            stopRecordingService();

            Log.d(TAG, "Segment rolling stopped and merged to: " + mergedFile.getName());
        } else {
            Log.e(TAG, "Segment rolling manager is null!");
            throw new IllegalStateException("Segment rolling manager not initialized");
        }

        // Stop wave level monitoring
        if (waveLevelEmitter != null) {
            waveLevelEmitter.stopMonitoring();
            Log.d(TAG, "Wave level monitoring stopped");
        }

        isRecording = false;

        // Get file info and return - with actual duration calculation
        // Create response with actual duration calculation
        JSObject response = createFileInfoWithDuration(fileToReturn);
        Log.d(TAG, "Recording stopped - File: " + new File(fileToReturn).getName());

        return response;
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        boolean hasActiveRecording = isRecording ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (hasActiveRecording) {
            JSObject result = new JSObject();

            if (segmentRollingManager != null) {
                // Use the segment manager's duration which respects the rolling window
                long segmentDuration = segmentRollingManager.getElapsedRecordingTime();
                result.put("duration", segmentDuration / 1000.0); // Convert to seconds
                Log.d(TAG, "Segment rolling current duration: " + (segmentDuration / 1000.0) + " seconds");
            } else {
                // If recording session is active but segment manager is null (reset state), return 0
                result.put("duration", 0.0);
                Log.d(TAG, "Recording session active but in reset state - duration: 0");
            }

            call.resolve(result);
        } else {
            call.reject("No active recording");
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        String status;
        boolean sessionActive = isRecording ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (sessionActive) {
            // If recording is active but segment manager is null or not active, it's in paused/reset state
            if (isRecording && (segmentRollingManager == null || !segmentRollingManager.isSegmentRollingActive())) {
                status = "paused"; // Reset state is effectively paused
            } else {
                status = "recording";
            }
        } else {
            status = "idle";
        }

        JSObject result = new JSObject();
        result.put("status", status);
        result.put("isRecording", sessionActive);

        if (segmentRollingManager != null) {
            // Use the segment manager's duration which respects the rolling window
            long segmentDuration = segmentRollingManager.getElapsedRecordingTime();
            result.put("duration", segmentDuration / 1000.0); // Convert to seconds
        } else {
            result.put("duration", 0.0);
        }

        call.resolve(result);
    }

    @PluginMethod
    public void getSegmentInfo(PluginCall call) {
        if (segmentRollingManager == null) {
            call.reject("No active segment rolling manager");
            return;
        }

        try {
            JSObject result = new JSObject();
            result.put("segmentCount", segmentRollingManager.getCurrentSegments().size());
            result.put("bufferedDuration", segmentRollingManager.getBufferedDuration() / 1000.0); // Convert to seconds
            result.put("segmentLength", segmentLengthSeconds);
            result.put("maxDuration", maxDurationSeconds);
            result.put("debugInfo", "");

            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get segment info", e);
            call.reject("Failed to get segment info: " + e.getMessage());
        }
    }

    @PluginMethod
    public void setDebugMode(PluginCall call) {
        boolean enabled = call.getBoolean("enabled", false);

        segmentRollingManager.setDebugMode(enabled);
        Log.d(TAG, "Debug mode " + (enabled ? "enabled" : "disabled") + " for segment rolling");

        call.resolve();
    }

    @PluginMethod
    public void trimAudio(PluginCall call) {
        try {
            // Validate input parameters
            String sourcePath = call.getString("uri");
            if (sourcePath == null) {
                call.reject(AudioEngineError.INVALID_URI.getCode(),
                           AudioEngineError.INVALID_URI.getMessage());
                return;
            }

            Double startTime = call.getDouble("start", 0.0);
            Double endTime = call.getDouble("end", 0.0);

            Log.d(TAG, "trimAudio called with URI: " + sourcePath + ", start: " + startTime + ", end: " + endTime);

            // Handle legacy URI formats (simplified for legacy compatibility)
            String actualPath = sourcePath;
            if (sourcePath.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = sourcePath.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (sourcePath.startsWith("file://")) {
                actualPath = sourcePath.substring(7);
            }

            Log.d(TAG, "Resolved URI to file path: " + actualPath);

            // Validate file exists and is accessible
            ValidationUtils.validateFileExists(actualPath);

            // Get actual audio duration and clamp end time if needed
            double audioDuration = AudioFileProcessor.getAudioDuration(actualPath);
            double actualEndTime = Math.min(endTime, audioDuration);

            // Validate time range with basic checks
            if (startTime < 0) {
                call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(), "Start time cannot be negative");
                return;
            }

            if (actualEndTime <= startTime) {
                call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(), "End time must be greater than start time");
                return;
            }

            if (startTime >= audioDuration) {
                call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(), "Start time cannot exceed audio duration (" + audioDuration + " seconds)");
                return;
            }

            Log.d(TAG, "Trimming audio from " + startTime + "s to " + actualEndTime + "s (original end: " + endTime + "s, duration: " + audioDuration + "s)");

            // Create output file
            File outputFile = fileManager.createProcessedFile("trimmed");

            // Perform trimming using AudioFileProcessor with clamped end time
            AudioFileProcessor.trimAudioFile(new File(actualPath), outputFile, startTime, actualEndTime);

            JSObject response = AudioFileProcessor.getAudioFileInfo(outputFile.getAbsolutePath(), getContext());
            call.resolve(response);

        } catch (SecurityException e) {
            Log.e(TAG, "Security error in trimAudio", e);
            call.reject(AudioEngineError.INVALID_FILE_PATH.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Validation error in trimAudio", e);
            call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(), e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim audio", e);
            call.reject(AudioEngineError.TRIMMING_FAILED.getCode(),
                       AudioEngineError.TRIMMING_FAILED.getDetailedMessage(e.getMessage()));
        }
    }

    @PluginMethod
    public void getAudioInfo(PluginCall call) {
        try {
            String uri = call.getString("uri");
            if (uri == null) {
                call.reject(AudioEngineError.INVALID_URI.getCode(),
                           AudioEngineError.INVALID_URI.getMessage());
                return;
            }

            // Handle legacy URI formats (simplified for legacy compatibility)
            String actualPath = uri;
            if (uri.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = uri.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (uri.startsWith("file://")) {
                actualPath = uri.substring(7);
            }

            JSObject response = AudioFileProcessor.getAudioFileInfo(actualPath, getContext());
            call.resolve(response);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio info", e);
            call.reject("AUDIO_INFO_ERROR", "Failed to get audio info: " + e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void isMicrophoneBusy(PluginCall call) {
        try {
            Log.d(TAG, "isMicrophoneBusy called");

            // Check if microphone permission is granted first using permissionService
            boolean hasPermission = hasPermission("microphone");
            Log.d(TAG, "Microphone permission granted: " + hasPermission);

            if (!hasPermission) {
                Log.d(TAG, "Microphone permission not granted, returning busy=true");
                JSObject result = new JSObject();
                result.put("busy", true);
                result.put("reason", "Microphone permission not granted");
                call.resolve(result);
                return;
            }

            // Check if our own app is currently recording
            boolean appRecording = isRecording || (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());
            Log.d(TAG, "App currently recording: " + appRecording + " (isRecording: " + isRecording + ", segmentRollingActive: " +
                  (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive()) + ")");

            if (appRecording) {
                Log.d(TAG, "App is recording, returning busy=true");
                JSObject result = new JSObject();
                result.put("busy", true);
                result.put("reason", "Recording in progress by this app");
                call.resolve(result);
                return;
            }

            // Test microphone availability by attempting to create and start an AudioRecord
            Log.d(TAG, "Testing microphone availability...");
            boolean isBusy = AudioRecordingConfig.checkMicrophoneAvailability();
            Log.d(TAG, "Microphone availability check result: busy=" + isBusy);

            // If the primary check says it's busy, try a secondary check with different parameters
            if (isBusy) {
                Log.d(TAG, "Primary check failed, trying secondary check...");
                isBusy = AudioRecordingConfig.checkMicrophoneAvailabilitySecondary();
                Log.d(TAG, "Secondary microphone check result: busy=" + isBusy);
            }

            JSObject result = new JSObject();
            result.put("busy", isBusy);
            if (isBusy) {
                result.put("reason", "Microphone is being used by another application");
            } else {
                result.put("reason", "Microphone is available");
            }

            Log.d(TAG, "isMicrophoneBusy returning: " + result);
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to check microphone status", e);
            JSObject result = new JSObject();
            result.put("busy", true);
            result.put("reason", "Failed to check microphone status: " + e.getMessage());
            call.resolve(result);
        }
    }

    @PluginMethod
    public void getAvailableMicrophones(PluginCall call) {
        try {
            JSArray microphones = AudioRecordingConfig.getAvailableMicrophonesArray(getContext());
            JSObject result = new JSObject();
            result.put("microphones", microphones);
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get available microphones", e);
            call.reject("MICROPHONES_ERROR", "Failed to get available microphones: " + e.getMessage());
        }
    }

    @PluginMethod
    public void switchMicrophone(PluginCall call) {
        try {
            Integer microphoneId = call.getInt("microphoneId");
            if (microphoneId == null) {
                call.reject("INVALID_PARAMETER", "Microphone ID is required");
                return;
            }

            // Note: Android doesn't provide direct microphone switching like iOS
            // The system handles microphone selection based on connected devices
            // and user preferences. We can only suggest preferred devices through AudioManager

            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                call.reject("AUDIO_MANAGER_ERROR", "Could not access AudioManager");
                return;
            }

          AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);

          for (AudioDeviceInfo device : devices) {
              if (device.getId() == microphoneId && AudioRecordingConfig.isInputDevice(device)) {
                  // Found the device, but Android doesn't allow direct switching
                  // The best we can do is inform that the device is available
                  Log.d(TAG, "Microphone switching requested for device: " + device.getProductName());

                  JSObject result = new JSObject();
                  result.put("switched", true);
                  result.put("microphoneId", microphoneId);
                  result.put("message", "Android handles microphone selection automatically");
                  call.resolve(result);
                  return;
              }
          }

          call.reject("MICROPHONE_NOT_FOUND", "Microphone with ID " + microphoneId + " not found");

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch microphone", e);
            call.reject("SWITCH_ERROR", "Failed to switch microphone: " + e.getMessage());
        }
    }

    @PluginMethod
    public void configureWaveform(PluginCall call) {
        try {
            // Derive current recording parameters
            int sr = (recordingConfig != null) ? recordingConfig.getSampleRate() : AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
            // Accept 'EmissionInterval' in ms from the call, fallback to 1000ms if not provided
            int intervalMs = call.getInt("EmissionInterval", 1000);

            if (waveLevelEmitter != null) {
                // Configure sample rate to match recording configuration
                waveLevelEmitter.setSampleRate(sr);

                // Configure emission interval
                waveLevelEmitter.setEmissionInterval(intervalMs);

                Log.d(TAG, "Wave level emitter configured - interval: " + intervalMs + "ms, sampleRate: " + sr + "Hz");

                // Build simplified result
                JSObject result = new JSObject();
                result.put("success", true);

                JSObject configuration = new JSObject();
                configuration.put("emissionInterval", intervalMs);
                result.put("configuration", configuration);

                call.resolve(result);

                // Start monitoring if not already active
                try {
                    if (!waveLevelEmitter.isMonitoring()) {
                        waveLevelEmitter.startMonitoring();
                        Log.d(TAG, "Wave level monitoring started via configuration");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Unable to start wave level monitoring from configuration", e);
                }
            } else {
                call.reject("WAVE_EMITTER_ERROR", "Wave level emitter not initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure waveform", e);
            call.reject("CONFIGURE_ERROR", "Failed to configure waveform: " + e.getMessage());
        }
    }








    @PluginMethod
    public void destroyWaveform(PluginCall call) {
        try {
            if (waveLevelEmitter != null) {
                // Stop monitoring if active
                if (waveLevelEmitter.isMonitoring()) {
                    Log.d(TAG, "Stopping wave level monitoring before destruction");
                }

                // Cleanup wave level resources
                waveLevelEmitter.cleanup();
                Log.d(TAG, "Wave level configuration destroyed and resources cleaned up");

                call.resolve();
            } else {
                call.reject("WAVE_EMITTER_ERROR", "Wave level emitter not initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to destroy waveform", e);
            call.reject("DESTROY_ERROR", "Failed to destroy waveform: " + e.getMessage());
        }
    }

    // Helper methods for the refactored implementation

    private void startSegmentRollingRecording() throws IOException {
        // Initialize segment rolling manager if needed
        if (segmentRollingManager == null) {
            File baseDirectory = fileManager.getRecordingsDirectory();

            // Create segment rolling configuration
            // Provide default values if not set
            int maxDuration = (maxDurationSeconds != null) ? maxDurationSeconds : 300; // Default 5 minutes
            int segmentLength = (segmentLengthSeconds != null) ? segmentLengthSeconds : 60; // Default 60 seconds (1 minute)

            Log.d(TAG, "Building segment rolling config:");
            Log.d(TAG, "  - maxDurationSeconds (from call): " + maxDurationSeconds);
            Log.d(TAG, "  - segmentLengthSeconds (from call): " + segmentLengthSeconds);
            Log.d(TAG, "  - final maxDuration: " + maxDuration + "s");
            Log.d(TAG, "  - final segmentLength: " + segmentLength + "s");

            // Additional validation before building config
            if (maxDuration <= 0) {
                Log.e(TAG, "ERROR: maxDuration is invalid: " + maxDuration);
                throw new IllegalArgumentException("maxDuration must be positive, got: " + maxDuration);
            }
            if (segmentLength <= 0) {
                Log.e(TAG, "ERROR: segmentLength is invalid: " + segmentLength);
                throw new IllegalArgumentException("segmentLength must be positive, got: " + segmentLength);
            }

            // Enhanced configuration with mobile-optimized defaults
            SegmentRollingConfig config = new SegmentRollingConfig.Builder()
                .setMaxDurationSeconds(maxDuration)
                .setSegmentLengthMs(segmentLength * 1000)  // Convert seconds to milliseconds
                .setBufferPaddingSegments(1)  // Keep 1 extra segment for safety
                .setQualityProfile(QualityProfile.BALANCED)  // Use balanced quality profile
                .setOutputDirectory(baseDirectory)
                .build();

            // Initialize segment rolling manager
            segmentRollingManager = new SegmentRollingManager(getContext(), config);
            Log.d(TAG, "Segment rolling manager initialized with config: " + config);
        }

        // Set duration change callback for segment rolling to emit events
        segmentRollingManager.setDurationChangeCallback(durationMs -> {
            // Emit duration change event for consistency with linear recording
            if (eventManager != null) {
                double durationSeconds = durationMs / 1000.0;
                eventManager.emitDurationChange(durationSeconds);
                Log.d(TAG, "Segment rolling duration change: " + durationSeconds + "s");
            }
        });

        // Start foreground service for background recording
        startRecordingService();

        // Start segment rolling
        try {
            Log.d(TAG, "Starting refactored segment rolling with segment length: " + segmentLengthSeconds + "s, max duration: " + maxDurationSeconds + "s");
            segmentRollingManager.startSegmentRolling();
            Log.d(TAG, "Refactored segment rolling start completed successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start refactored segment rolling", e);
            throw e;
        }

        // Start wave level monitoring for real-time audio levels
        if (waveLevelEmitter != null) {
            waveLevelEmitter.startMonitoring();
            Log.d(TAG, "Wave level monitoring started");
        }

        isRecording = true;

        Log.d(TAG, "Started refactored segment rolling recording with segment length: " + segmentLengthSeconds + "s, max duration: " + maxDurationSeconds + "s");

        // Emit recording state change event
        JSObject stateData = new JSObject();
        eventManager.emitRecordingStateChange("recording", stateData);
    }

    private void cleanupRecordingState() {
        try {
            Log.d(TAG, "Cleaning up recording state...");

            // Stop and release segment rolling manager
            if (segmentRollingManager != null) {
                try {
                    segmentRollingManager.shutdown();
                } catch (Exception e) {
                    Log.w(TAG, "Error shutting down segment rolling manager during cleanup", e);
                }
                segmentRollingManager = null;
            }

            // Stop waveform data monitoring
            if (waveLevelEmitter != null) {
                try {
                    waveLevelEmitter.stopMonitoring();
                    Log.d(TAG, "Wave level monitoring cleaned up");
                } catch (Exception e) {
                    Log.w(TAG, "Error cleaning up wave level emitter", e);
                }
            }

            // Stop recording service
            stopRecordingService();

            // Reset all recording state flags
            isRecording = false;
            isSegmentRollingEnabled = false;

            Log.d(TAG, "Recording state cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
            // Force reset state even if cleanup partially failed
            isRecording = false;
            isSegmentRollingEnabled = false;
        }
    }

    /**
     * Create file info with actual duration calculation for stop recording response
     */
    private JSObject createFileInfoWithDuration(String filePath) {
        try {
            File file = new File(filePath);

            // Ensure file exists and is properly finalized
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: " + filePath);
                throw new IllegalStateException("Recording file not found: " + filePath);
            }

            // Wait briefly to ensure file is fully written and finalized
            if (file.length() > 0) {
                try {
                    // Brief pause to ensure file system operations are complete
                    Thread.sleep(50);

                    // Force file system sync by checking file properties
                    long fileSize = file.length();
                    long lastModified = file.lastModified();
                    Log.d(TAG, "File ready for metadata reading - Size: " + fileSize + " bytes, Modified: " + lastModified);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "File sync wait interrupted");
                }
            }

            // Use the enhanced AudioFileProcessor method with secure URIs
            JSObject info = AudioFileProcessor.getAudioFileInfo(filePath, getContext());

            // Override/add specific fields for recording response format
            info.put("mimeType", "audio/m4a"); // Ensure M4A format for recordings

            // Handle empty files (created after reset with no recording)
            if (file.length() == 0) {
                Log.d(TAG, "Empty file detected (created after reset), setting duration to 0");
                info.put("duration", 0.0);
            } else {
                // Verify duration is correctly set - get it again if it's 0
                double duration = info.optDouble("duration", 0.0);
                if (duration == 0.0) {
                    Log.w(TAG, "Initial duration was 0, attempting direct calculation");
                    // Try direct duration calculation as fallback
                    double directDuration = AudioFileProcessor.getAudioDuration(filePath);
                    if (directDuration > 0) {
                        info.put("duration", directDuration);
                        Log.d(TAG, "Successfully recovered duration using direct calculation: " + directDuration + "s");
                    } else {
                        Log.e(TAG, "Failed to get duration even with direct calculation for file: " + file.getName());

                        // Final fallback: estimate duration based on file size and bitrate
                        int bitrate = info.optInt("bitrate", 0);

                        // If bitrate is 0 from metadata, try to use recording config bitrate
                        if (bitrate == 0 && recordingConfig != null) {
                            bitrate = recordingConfig.getBitrate();
                            Log.w(TAG, "Using recording config bitrate for estimation: " + bitrate);
                        }

                        // If still no bitrate, use a reasonable default for M4A AAC
                        if (bitrate == 0) {
                            bitrate = 128000; // 128 kbps is a common default for AAC
                            Log.w(TAG, "Using default bitrate for estimation: " + bitrate);
                        }

                        if (file.length() > 0) {
                            // Estimated duration = (file size in bits) / (bitrate in bits per second)
                            double estimatedDuration = (file.length() * 8.0) / bitrate;
                            if (estimatedDuration > 0 && estimatedDuration < 3600) { // Sanity check: less than 1 hour
                                info.put("duration", estimatedDuration);
                                Log.w(TAG, "Using estimated duration based on file size and bitrate (" + bitrate + "): " +
                                      estimatedDuration + "s for " + file.getName());
                            } else {
                                Log.e(TAG, "Estimated duration seems unrealistic: " + estimatedDuration + "s");
                            }
                        } else {
                            Log.e(TAG, "Cannot estimate duration - file size is 0");
                        }
                    }
                }
            }

            // Override with current recording config if available
            if (recordingConfig != null) {
                info.put("sampleRate", recordingConfig.getSampleRate());
                info.put("channels", recordingConfig.getChannels());
                info.put("bitrate", recordingConfig.getBitrate());
            }

            double finalDuration = info.optDouble("duration", 0.0);
            Log.d(TAG, "Created file info with duration: " + finalDuration + "s for: " + file.getName() +
                  " (size: " + file.length() + " bytes)");

            return info;
        } catch (Exception e) {
            Log.e(TAG, "Error creating file info with duration", e);
            // Fallback to basic info
            JSObject fallbackInfo = new JSObject();
            File file = new File(filePath);
            fallbackInfo.put("path", filePath);
            fallbackInfo.put("filename", file.getName());
            fallbackInfo.put("size", file.length());
            fallbackInfo.put("duration", 0.0);
            fallbackInfo.put("error", e.getMessage());
            return fallbackInfo;
        }
    }

    // ==================== PLAYBACK METHODS ====================

    @PluginMethod
    public void preloadTracks(PluginCall call) {
        try {
            JSArray tracksArray = call.getArray("tracks");
            if (tracksArray == null) {
                call.reject("Invalid tracks array - expected array of URLs");
                return;
            }

            List<String> trackUrls = new ArrayList<>();

            for (int i = 0; i < tracksArray.length(); i++) {
                String url = tracksArray.getString(i);
                if (url == null || url.trim().isEmpty()) {
                    call.reject("Invalid track URL at index " + i);
                    return;
                }
                trackUrls.add(url);
            }

            // Pass preloadNext parameter to PlaybackManager
            List<JSObject> trackResults = playbackManager.preloadTracks(trackUrls);

            // Convert List<JSObject> to JSArray to ensure proper JSON serialization
            JSArray resultTracksArray = new JSArray();
            for (JSObject trackResult : trackResults) {
                resultTracksArray.put(trackResult);
            }

            JSObject result = new JSObject();
            result.put("tracks", resultTracksArray);
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to preload tracks", e);
            call.reject("Failed to preload tracks: " + e.getMessage());
        }
    }

    @PluginMethod
    public void playAudio(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url != null) {
                // Play specific preloaded track by URL
                playbackManager.playByUrl(url);
            } else {
                // Play current track
                playbackManager.play();
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play audio", e);
            call.reject("Failed to play audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pauseAudio(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url != null) {
                // Pause specific preloaded track by URL
                playbackManager.pauseByUrl(url);
            } else {
                // Pause current track
                playbackManager.pause();
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause audio", e);
            call.reject("Failed to pause audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void resumeAudio(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url != null) {
                // Resume specific preloaded track by URL
                playbackManager.resumeByUrl(url);
            } else {
                // Resume current track
                playbackManager.resume();
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume audio", e);
            call.reject("Failed to resume audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopAudio(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url != null) {
                // Stop specific preloaded track by URL
                playbackManager.stopByUrl(url);
            } else {
                // Stop current track
                playbackManager.stop();
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop audio", e);
            call.reject("Failed to stop audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void seekAudio(PluginCall call) {
        Double seconds = call.getDouble("seconds");
        if (seconds == null) {
            call.reject("Missing seconds parameter");
            return;
        }

        String url = call.getString("url");
        if (url != null) {
            // Seek in specific preloaded track by URL
            playbackManager.seekByUrl(url, seconds);
        } else {
            // Seek in current track
            playbackManager.seekTo(seconds);
        }
        call.resolve();
    }

    @PluginMethod
    public void skipToNext(PluginCall call) {
        playbackManager.skipToNext();
        call.resolve();
    }

    @PluginMethod
    public void skipToPrevious(PluginCall call) {
        playbackManager.skipToPrevious();
        call.resolve();
    }

    @PluginMethod
    public void skipToIndex(PluginCall call) {
        Integer index = call.getInt("index");
        if (index == null) {
            call.reject("Missing index parameter");
            return;
        }

        playbackManager.skipToIndex(index);
        call.resolve();
    }

    @PluginMethod
    public void getPlaybackInfo(PluginCall call) {
        // Ensure we're on the main thread since ExoPlayer methods need to be called from main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> getPlaybackInfo(call));
            return;
        }

        try {
            JSObject result = new JSObject();

            AudioTrack currentTrack = playbackManager.getCurrentTrack();
            if (currentTrack != null) {
                result.put("currentTrack", currentTrack.toJSON());
            } else {
                result.put("currentTrack", JSObject.NULL);
            }

            result.put("currentIndex", playbackManager.getCurrentIndex());
            result.put("currentPosition", playbackManager.getCurrentPosition());
            result.put("duration", playbackManager.getDuration());
            result.put("isPlaying", playbackManager.isPlaying());
            result.put("status", statusToString(playbackManager.getStatus()));

            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get playback info: " + e.getMessage());
        }
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            String packageName = getContext().getPackageName();
            Log.d(TAG, "Attempting to open app settings for package: " + packageName);

            // Primary approach: Direct app info/permissions page (works on all Android versions)
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(android.net.Uri.parse("package:" + packageName));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                // Verify the intent can be resolved
                if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                    getContext().startActivity(intent);
                    Log.d(TAG, "Successfully opened app settings using ACTION_APPLICATION_DETAILS_SETTINGS");
                    call.resolve(createSuccessResponse("ACTION_APPLICATION_DETAILS_SETTINGS"));
                    return;
                } else {
                    Log.d(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS intent cannot be resolved");
                }
            } catch (Exception e) {
                Log.d(TAG, "ACTION_APPLICATION_DETAILS_SETTINGS failed: " + e.getMessage());
            }

            // Fallback approach: Open general device settings if app-specific settings fail
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                if (intent.resolveActivity(getContext().getPackageManager()) != null) {
                    getContext().startActivity(intent);
                    Log.d(TAG, "Successfully opened general settings as fallback");
                    call.resolve(createSuccessResponse("ACTION_SETTINGS_FALLBACK"));
                    return;
                }
            } catch (Exception e) {
                Log.d(TAG, "General settings fallback failed: " + e.getMessage());
            }

            // If all attempts fail, this is highly unlikely on any Android device
            Log.e(TAG, "All attempts to open settings failed");
            call.reject("SETTINGS_UNAVAILABLE", "Unable to open device settings - no suitable intent found");

        } catch (Exception e) {
            Log.e(TAG, "Failed to open app settings", e);
            call.reject("SETTINGS_ERROR", "Failed to open app settings: " + e.getMessage());
        }
    }

    private JSObject createSuccessResponse(String method) {
        JSObject result = new JSObject();
        result.put("opened", true);
        result.put("method", method);
        result.put("packageName", getContext().getPackageName());
        return result;
    }



    private String statusToString(PlaybackStatus status) {
      return switch (status) {
        case LOADING -> "loading";
        case PLAYING -> "playing";
        case PAUSED -> "paused";
        case STOPPED -> "stopped";
        default -> "idle";
      };
    }

    // PlaybackManagerListener implementation
    @Override
    public void onTrackChanged(AudioTrack track, int index) {
        try {
            JSObject data = new JSObject();
            data.put("track", track.toJSON());
            data.put("index", index);
            notifyListeners("trackChanged", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying track changed", e);
        }
    }

    @Override
    public void onTrackEnded(AudioTrack track, int index) {
        try {
            JSObject data = new JSObject();
            data.put("track", track.toJSON());
            data.put("index", index);
            notifyListeners("trackEnded", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying track ended", e);
        }
    }

    @Override
    public void onPlaybackStarted(AudioTrack track, int index) {
        try {
            JSObject data = new JSObject();
            data.put("track", track.toJSON());
            data.put("index", index);
            notifyListeners("playbackStarted", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback started", e);
        }
    }

    @Override
    public void onPlaybackPaused(AudioTrack track, int index) {
        try {
            JSObject data = new JSObject();
            data.put("track", track.toJSON());
            data.put("index", index);
            data.put("position", playbackManager.getCurrentPosition());
            notifyListeners("playbackPaused", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback paused", e);
        }
    }

    @Override
    public void onPlaybackError(String error) {
        JSObject data = new JSObject();
        data.put("message", error);
        notifyListeners("playbackError", data);
    }

    @Override
    public void onPlaybackProgress(AudioTrack track, int index, long currentPosition, long duration, boolean isPlaying) {
        try {
            JSObject data = new JSObject();
            data.put("track", track.toJSON());
            data.put("index", index);
            data.put("currentPosition", currentPosition / 1000.0); // Convert to seconds
            data.put("duration", duration / 1000.0); // Convert to seconds
            data.put("isPlaying", isPlaying);
            notifyListeners("playbackProgress", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback progress", e);
        }
    }

    @Override
    public void onPlaybackStatusChanged(AudioTrack track, int index, PlaybackStatus status, long currentPosition, long duration, boolean isPlaying) {
        try {
            JSObject data = new JSObject();
            if (track != null) {
                data.put("track", track.toJSON());
            } else {
                data.put("track", JSObject.NULL);
            }
            data.put("index", index);
            data.put("status", status.name().toLowerCase());
            data.put("currentPosition", currentPosition / 1000.0); // Convert to seconds
            data.put("duration", duration / 1000.0); // Convert to seconds
            data.put("isPlaying", isPlaying);
            notifyListeners("playbackStatusChanged", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback status changed", e);
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();

        Log.d(TAG, "Plugin destroying - cleaning up all resources");

        try {
            // Clean up recording state
            cleanupRecordingState();

            // Release managers
            if (playbackManager != null) {
                playbackManager.release();
                playbackManager = null;
            }

            if (eventManager != null) {
                eventManager = null;
            }

            if (fileManager != null) {
                fileManager = null;
            }

            // Clean up waveform data manager
            if (waveLevelEmitter != null) {
                waveLevelEmitter.cleanup();
                waveLevelEmitter = null;
            }

            // Unbind recording service
            unbindRecordingService();

            // Clear main handler
            if (mainHandler != null) {
                mainHandler.removeCallbacksAndMessages(null);
                mainHandler = null;
            }

            Log.i(TAG, "CapacitorAudioEngine plugin destroyed - all resources cleaned up");

        } catch (Exception e) {
            Log.e(TAG, "Error during plugin destruction", e);
        }
    }

    // MARK: - Background Recording Service Integration

    /**
     * Bind to the background recording service
     */
    private void bindRecordingService() {
        if (!isServiceBound) {
            Intent intent = new Intent(getContext(), AudioRecordingService.class);
            getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            Log.d(TAG, "Binding to AudioRecordingService");
        }
    }

    /**
     * Unbind from the background recording service
     */
    private void unbindRecordingService() {
        if (isServiceBound) {
            try {
                getContext().unbindService(serviceConnection);
                isServiceBound = false;
                recordingService = null;
                Log.d(TAG, "Unbound from AudioRecordingService");
            } catch (Exception e) {
                Log.w(TAG, "Error unbinding from recording service", e);
            }
        }
    }

    /**
     * Start foreground service for background recording
     */
    private void startRecordingService() {
        Intent intent = new Intent(getContext(), AudioRecordingService.class);
        intent.setAction("START_RECORDING");
        getContext().startForegroundService(intent);

        // Bind to service for communication
        bindRecordingService();
    }

    /**
     * Stop foreground service
     */
    private void stopRecordingService() {
        if (recordingService != null) {
            recordingService.stopForegroundRecording();
        }
        unbindRecordingService();
    }

    // MARK: - AudioInterruptionManager.InterruptionCallback Implementation

    @Override
    public void onInterruptionBegan(AudioInterruptionManager.InterruptionType type) {
        Log.d(TAG, "Audio interruption began: " + type);
        // Segment rolling handles its own interruptions, so no action needed here
    }

    @Override
    public void onInterruptionEnded(AudioInterruptionManager.InterruptionType type, boolean shouldResume) {
        Log.d(TAG, "Audio interruption ended: " + type + ", should resume: " + shouldResume);
        // Segment rolling handles its own interruptions, so no action needed here
    }

    @Override
    public void onAudioRouteChanged(String reason) {
        Log.d(TAG, "Audio route changed: " + reason);
        // Handle audio route changes if needed
    }

    /**
     * Internal pause recording method that can be called without PluginCall
     */
    private void pauseRecordingInternal(PluginCall call) {
        if (!isRecording && (segmentRollingManager == null || !segmentRollingManager.isSegmentRollingActive())) {
            if (call != null) call.reject("No active recording session to pause");
            return;
        }

        try {
            // Pause segment rolling
            if (segmentRollingManager != null) {
                segmentRollingManager.pauseSegmentRolling();
                Log.d(TAG, "Segment rolling recording paused");

                // Pause wave level monitoring
                if (waveLevelEmitter != null) {
                    waveLevelEmitter.pauseMonitoring();
                    Log.d(TAG, "Wave level monitoring paused");
                }

                // Emit pause state change event for consistency
                if (eventManager != null) {
                    JSObject pauseData = new JSObject();
                    long currentDuration = segmentRollingManager.getElapsedRecordingTime();
                    pauseData.put("duration", currentDuration / 1000.0);
                    pauseData.put("isRecording", true); // Session is still active
                    pauseData.put("status", "paused");
                    eventManager.emitRecordingStateChange("paused", pauseData);
                }

                if (call != null) call.resolve();
            } else {
                if (call != null) call.reject("Segment rolling manager not initialized");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause recording", e);
            if (call != null) call.reject("Failed to pause recording: " + e.getMessage());
        }
    }

    /**
     * Internal resume recording method that can be called without PluginCall
     */
    private void resumeRecordingInternal(PluginCall call) {
        if (!isRecording) {
            if (call != null) call.reject("No recording session active");
            return;
        }

        try {
            // Resume segment rolling
            if (segmentRollingManager != null) {
                if (segmentRollingManager.isSegmentRollingActive()) {
                    // Normal resume case - manager is active (including after reset with paused segment)
                    segmentRollingManager.resumeSegmentRolling();
                    Log.d(TAG, "Segment rolling recording resumed from pause/reset state");
                } else {
                    // Legacy case - manager exists but not active, need to restart fresh
                    Log.d(TAG, "Segment rolling not active (legacy state), restarting segment rolling");
                    segmentRollingManager.startSegmentRolling();
                    Log.d(TAG, "Fresh segment rolling started after legacy reset");
                }

                // Ensure wave level monitoring restarts fresh if it was stopped by reset
                if (waveLevelEmitter != null) {
                    try {
                        if (!waveLevelEmitter.isMonitoring()) {
                            waveLevelEmitter.startMonitoring();
                            Log.d(TAG, "Wave level monitoring started");
                        } else {
                            waveLevelEmitter.resumeMonitoring();
                            Log.d(TAG, "Wave level monitoring resumed");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Unable to start/resume wave level monitoring", e);
                    }
                }

                // Emit recording state change event for consistency
                if (eventManager != null) {
                    JSObject resumeData = new JSObject();
                    long currentDuration = segmentRollingManager.getElapsedRecordingTime();
                    resumeData.put("duration", currentDuration / 1000.0);
                    resumeData.put("isRecording", true);
                    resumeData.put("status", "recording");
                    eventManager.emitRecordingStateChange("recording", resumeData);
                }

                if (call != null) call.resolve();
            } else {
                // If segment rolling manager is null, recreate it
                Log.d(TAG, "Segment rolling manager not initialized, recreating for resume");
                try {
                    // Use M4A format as default for resume (could be made configurable)
                    startSegmentRollingRecording();
                    Log.d(TAG, "Segment rolling recording resumed with fresh session");
                    if (call != null) call.resolve();
                } catch (Exception ex) {
                    Log.e(TAG, "Failed to start fresh segment rolling session", ex);
                    if (call != null) call.reject("Failed to resume recording: " + ex.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume recording", e);
            if (call != null) call.reject("Failed to resume recording: " + e.getMessage());
        }
    }
}
