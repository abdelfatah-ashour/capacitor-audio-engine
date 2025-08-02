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
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;

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
public class CapacitorAudioEnginePlugin extends Plugin implements PermissionManager.PermissionRequestCallback, EventManager.EventCallback, PlaybackManager.PlaybackManagerListener, RecordingServiceListener {
    private static final String TAG = "CapacitorAudioEngine";

    // Core managers
    private PermissionManager permissionManager;
    private EventManager eventManager;
    private MediaRecorderManager recorderManager;
    private DurationMonitor durationMonitor;

    private FileDirectoryManager fileManager;
    private PlaybackManager playbackManager;

    // Background recording service
    private RecordingService recordingService;
    private boolean isServiceBound = false;

    // Segment rolling support
    private SegmentRollingManager segmentRollingManager;
    private boolean isSegmentRollingEnabled = false;

    // Recording state
    private boolean isRecording = false;
    private String currentRecordingPath;
    private AudioRecordingConfig recordingConfig;
    private Integer maxDurationSeconds; // Maximum recording duration in seconds

    // Thread management
    private Handler mainHandler;

    @Override
    public void load() {
        super.load();
        mainHandler = new Handler(Looper.getMainLooper());

        // Initialize managers
        permissionManager = new PermissionManager(getContext(), this);
        eventManager = new EventManager(this);
        fileManager = new FileDirectoryManager(getContext());


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
                recordingService.setRecordingServiceListener(CapacitorAudioEnginePlugin.this);
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
        // Initialize duration monitor with callback
        durationMonitor = new DurationMonitor(mainHandler, new DurationMonitor.DurationCallback() {
            @Override
            public void onDurationChanged(double duration) {
                eventManager.emitDurationChange(duration);
            }

            @Override
            public void onMaxDurationReached() {
                // Only automatically stop for linear recording, not segment rolling
                if (!isSegmentRollingEnabled) {
                    try {
                        Log.d(TAG, "Max duration reached for linear recording, automatically stopping");
                        JSObject result = stopRecordingInternal();

                        // Emit event to notify frontend that recording was stopped due to max duration
                        JSObject eventData = new JSObject();
                        eventData.put("reason", "maxDurationReached");
                        eventData.put("fileInfo", result);
                        eventManager.emitRecordingStateChange("stopped", eventData);

                    } catch (Exception e) {
                        Log.e(TAG, "Error stopping recording at max duration", e);
                        JSObject errorData = new JSObject();
                        errorData.put("message", "Error stopping recording at max duration: " + e.getMessage());
                        eventManager.emitError(errorData);
                    }
                } else {
                    // For segment rolling, just emit duration change but don't stop
                    Log.d(TAG, "Max duration reached for segment rolling, continuing to record in rolling segments");
                }
            }
        });

        // Initialize recorder manager
        recorderManager = new MediaRecorderManager();
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
        JSObject result = permissionManager.checkPermissions();
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        permissionManager.requestPermissions(call);
    }

    // Backward compatibility methods (deprecated)
    @PluginMethod
    public void checkPermission(PluginCall call) {
        checkPermissions(call);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        requestPermissions(call);
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        JSObject result = permissionManager.checkPermissions();
        call.resolve(result);
    }

  // Implementation of PermissionRequestCallback interface
    @Override
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
        Log.d(TAG, "startRecording called - current state: isRecording=" + isRecording +
              ", recorderManager.isRecording()=" + recorderManager.isRecording());

        // Validate state
        if (recorderManager.isRecording()) {
            call.reject(AudioEngineError.RECORDING_IN_PROGRESS.getCode(),
                       AudioEngineError.RECORDING_IN_PROGRESS.getMessage());
            return;
        }

        // Check permissions
        permissionManager.validateRecordingPermissions();

        // Clean up any leftover state from previous recordings or interruptions
        cleanupRecordingState();

        // Reset states
        durationMonitor.resetDuration();

        // Get and validate recording options
        String quality = call.getString("quality");
        int sampleRate = getIntegerSafely(call, "sampleRate", AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE);
        int channels = getIntegerSafely(call, "channels", AudioEngineConfig.Recording.DEFAULT_CHANNELS);
        int bitrate = getIntegerSafely(call, "bitrate", AudioEngineConfig.Recording.DEFAULT_BITRATE);

        // Create recording configuration
        recordingConfig = new AudioRecordingConfig.Builder()
            .sampleRate(sampleRate)
            .channels(channels)
            .bitrate(bitrate)
            .build();

        Log.d(TAG, "Using config - Quality: " + quality + ", SampleRate: " + sampleRate +
              ", Channels: " + channels + ", Bitrate: " + bitrate);

        Integer maxDuration = call.getInt("maxDuration"); // Can be null

        // Store maxDuration for use in recording logic
        maxDurationSeconds = maxDuration;

        // Validate audio parameters
        ValidationUtils.validateAudioParameters(sampleRate, channels, bitrate);

        Log.d(TAG, "Starting recording with maxDuration: " + maxDuration + " seconds");

        // Check if maxDuration is provided to enable segment rolling
        if (maxDuration != null && maxDuration > 0) {
            isSegmentRollingEnabled = true;
            Log.d(TAG, "Enabling segment rolling mode for maxDuration: " + maxDuration + " seconds");
            try {
                startSegmentRollingRecording();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start segment rolling recording", e);
                cleanupRecordingState();
                throw e;
            }
        } else {
            isSegmentRollingEnabled = false;
            Log.d(TAG, "Enabling linear recording mode (no maxDuration specified)");
            try {
                startLinearRecording();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start linear recording", e);
                cleanupRecordingState();
                throw e;
            }
        }

        call.resolve();
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (!isRecording && (recorderManager == null || !recorderManager.isRecording()) &&
            (segmentRollingManager == null || !segmentRollingManager.isSegmentRollingActive())) {
            call.reject("No active recording session to pause");
            return;
        }

        try {
            if (isSegmentRollingEnabled) {
                // Pause segment rolling
                if (segmentRollingManager != null) {
                    segmentRollingManager.pauseSegmentRolling();
                    // Do not stop duration monitor for segment rolling since we're not using it

                    Log.d(TAG, "Segment rolling recording paused");
                    call.resolve();
                } else {
                    call.reject("Segment rolling manager not initialized");
                }
            } else {
                // Pause linear recording
                if (recorderManager.isPauseResumeSupported()) {
                    recorderManager.pauseRecording();
                    durationMonitor.stopMonitoring();

                    Log.d(TAG, "Linear recording paused");
                    call.resolve();
                } else {
                    call.reject("Pause/Resume is not supported on Android versions below API 24");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause recording", e);
            call.reject("Failed to pause recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (!isRecording) {
            call.reject("No recording session active");
            return;
        }

        try {
            if (isSegmentRollingEnabled) {
                // Resume segment rolling
                if (segmentRollingManager != null) {
                    segmentRollingManager.resumeSegmentRolling();
                    // Do not start duration monitor for segment rolling since we're not using it

                    Log.d(TAG, "Segment rolling recording resumed");
                    call.resolve();
                } else {
                    call.reject("Segment rolling manager not initialized");
                }
            } else {
                // Resume linear recording
                if (recorderManager.isPauseResumeSupported()) {
                    recorderManager.resumeRecording();
                    durationMonitor.startMonitoring();

                    Log.d(TAG, "Linear recording resumed");
                    call.resolve();
                } else {
                    call.reject("Pause/Resume is not supported on Android versions below API 24");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume recording", e);
            call.reject("Failed to resume recording: " + e.getMessage());
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
            (recorderManager != null && recorderManager.isRecording()) ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (!hasActiveRecording) {
            throw new IllegalStateException("No active recording to stop");
        }

        // Stop duration monitoring only for linear recording
        if (!isSegmentRollingEnabled) {
            durationMonitor.stopMonitoring();
        }

        String fileToReturn;

        if (isSegmentRollingEnabled) {
            Log.d(TAG, "Stopping segment rolling recording...");
            // Stop segment rolling and merge segments
            if (segmentRollingManager != null) {
                File mergedFile = segmentRollingManager.stopSegmentRolling();
                fileToReturn = mergedFile.getAbsolutePath();

                // Stop recording service as recording is complete
                stopRecordingService();

                Log.d(TAG, "Segment rolling stopped and merged to: " + mergedFile.getName());
            } else {
                Log.e(TAG, "Segment rolling manager is null!");
                throw new IllegalStateException("Segment rolling manager not initialized");
            }
        } else {
            Log.d(TAG, "Stopping linear recording...");
            // Stop linear recording
            if (recorderManager != null && recorderManager.isRecording()) {
                recorderManager.stopRecording();
            }
            if (recorderManager != null) {
                recorderManager.release();
            }
            fileToReturn = currentRecordingPath;

            // Stop recording service as recording is complete
            stopRecordingService();
        }

        isRecording = false;

        // Get file info and return - with actual duration calculation
        if (fileToReturn != null) {
            // Create response with actual duration calculation
            JSObject response = createFileInfoWithDuration(fileToReturn);
            Log.d(TAG, "Recording stopped - File: " + new File(fileToReturn).getName());

            // Cleanup
            currentRecordingPath = null;

            return response;
        } else {
            throw new IllegalStateException("No recording file to return");
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        boolean hasActiveRecording = isRecording ||
            (recorderManager != null && recorderManager.isRecording()) ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (hasActiveRecording) {
            JSObject result = new JSObject();

            if (isSegmentRollingEnabled && segmentRollingManager != null) {
                // For segment rolling, use the segment manager's duration which respects the rolling window
                long segmentDuration = segmentRollingManager.getCurrentDuration();
                result.put("duration", segmentDuration / 1000.0); // Convert to seconds
                Log.d(TAG, "Segment rolling current duration: " + (segmentDuration / 1000.0) + " seconds");
            } else {
                // For linear recording, use duration monitor
                result.put("duration", durationMonitor.getCurrentDuration());
                Log.d(TAG, "Linear recording current duration: " + durationMonitor.getCurrentDuration() + " seconds");
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
            (recorderManager != null && recorderManager.isRecording()) ||
            (segmentRollingManager != null && segmentRollingManager.isSegmentRollingActive());

        if (sessionActive) {
            status = "recording";
        } else {
            status = "idle";
        }

        JSObject result = new JSObject();
        result.put("status", status);
        result.put("isRecording", sessionActive);

        if (isSegmentRollingEnabled && segmentRollingManager != null) {
            // For segment rolling, use the segment manager's duration which respects the rolling window
            long segmentDuration = segmentRollingManager.getCurrentDuration();
            result.put("duration", segmentDuration / 1000.0); // Convert to seconds
        } else {
            // For linear recording, use duration monitor
            result.put("duration", durationMonitor.getCurrentDuration());
        }

        call.resolve(result);
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

            // Validate time range
            ValidationUtils.validateTimeRange(startTime, endTime);

            // Handle Capacitor file URI format
            String actualPath = sourcePath;
            if (sourcePath.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = sourcePath.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (sourcePath.startsWith("file://")) {
                actualPath = sourcePath.substring(7);
            }

            // Validate file exists and is accessible
            ValidationUtils.validateFileExists(actualPath);

            // Create output file
            File outputFile = fileManager.createProcessedFile("trimmed");

            // Perform trimming using AudioFileProcessor
            AudioFileProcessor.trimAudioFile(new File(actualPath), outputFile, startTime, endTime);

            // Return file info
            JSObject response = AudioFileProcessor.getAudioFileInfo(outputFile.getAbsolutePath());
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

            // Handle Capacitor file URI format
            String actualPath = uri;
            if (uri.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = uri.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (uri.startsWith("file://")) {
                actualPath = uri.substring(7);
            }

            JSObject response = AudioFileProcessor.getAudioFileInfo(actualPath);
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

            // Check if microphone permission is granted first
            boolean hasPermission = permissionManager.hasMicrophonePermission();
            Log.d(TAG, "Microphone permission granted: " + hasPermission);

            // Also log the full permission result for debugging
            JSObject permissionResult = permissionManager.checkPermissions();
            Log.d(TAG, "Full permission result: " + permissionResult);

            if (!hasPermission) {
                Log.d(TAG, "Microphone permission not granted, returning busy=true");
                JSObject result = new JSObject();
                result.put("busy", true);
                result.put("reason", "Microphone permission not granted");
                call.resolve(result);
                return;
            }

            // Check if our own app is currently recording
            boolean appRecording = isRecording || recorderManager.isRecording();
            Log.d(TAG, "App currently recording: " + appRecording + " (isRecording: " + isRecording + ", recorderManager.isRecording(): " + recorderManager.isRecording() + ")");

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

    // Helper methods for the refactored implementation

    private void startLinearRecording() throws IOException {


        // Set maximum duration if provided
        if (maxDurationSeconds != null && maxDurationSeconds > 0) {
            durationMonitor.setMaxDuration(maxDurationSeconds);
        }

        File audioFile = fileManager.createRecordingFile("recording");
        currentRecordingPath = audioFile.getAbsolutePath();

        // Start foreground service for background recording
        startRecordingService();

        recorderManager.setupMediaRecorder(currentRecordingPath, recordingConfig);
        recorderManager.startRecording();

        isRecording = true;
        durationMonitor.startMonitoring();

        Log.d(TAG, "Started linear recording: " + audioFile.getName() + " with maxDuration: " + maxDurationSeconds + " seconds");

        // Emit recording state change event to match iOS behavior
        JSObject stateData = new JSObject();
        eventManager.emitRecordingStateChange("recording", stateData);
    }

    private void startSegmentRollingRecording() throws IOException {
        // Initialize segment rolling manager if needed
        if (segmentRollingManager == null) {
            File baseDirectory = fileManager.getRecordingsDirectory();
            // Initialize segment rolling manager with context for interruption handling
            segmentRollingManager = new SegmentRollingManager(getContext(), baseDirectory);



        }

        // Set maximum duration in segment rolling manager for rolling window management
        if (maxDurationSeconds != null && maxDurationSeconds > 0) {
            segmentRollingManager.setMaxDuration(maxDurationSeconds * 1000); // Convert to milliseconds
            Log.d(TAG, "Segment rolling will maintain a rolling window of " + maxDurationSeconds + " seconds");
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

        // Configure enhanced features for production robustness
        segmentRollingManager.configureSafetyMargins(1.5, 4); // 50% memory margin, 4 extra segments for 30s precision
        segmentRollingManager.setSegmentCompressionEnabled(false); // Disable compression by default
        segmentRollingManager.setPersistentIndexingEnabled(true); // Enable crash recovery

        // Clear max duration from duration monitor since we're not using it for segment rolling
        durationMonitor.setMaxDuration(null);

        // Start foreground service for background recording
        startRecordingService();

        // Start segment rolling
        segmentRollingManager.startSegmentRolling(recordingConfig);

        isRecording = true;

        // Do NOT start duration monitoring for segment rolling - the SegmentRollingManager handles its own duration tracking
        // durationMonitor.startMonitoring(); // Commented out to avoid conflicts

        Log.d(TAG, "Started segment rolling recording with rolling window: " + maxDurationSeconds + " seconds");

        // Emit recording state change event
        JSObject stateData = new JSObject();
        eventManager.emitRecordingStateChange("recording", stateData);
    }

    private void cleanupRecordingState() {
        try {
            Log.d(TAG, "Cleaning up recording state...");

            // Stop and release recorder
            if (recorderManager != null) {
                try {
                    if (recorderManager.isRecording()) {
                        recorderManager.stopRecording();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping recorder during cleanup", e);
                }
                try {
                    recorderManager.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing recorder during cleanup", e);
                }
            }

            // Stop and release segment rolling manager
            if (segmentRollingManager != null) {
                try {
                    segmentRollingManager.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing segment rolling manager during cleanup", e);
                }
                segmentRollingManager = null;
            }

            // Stop recording service
            stopRecordingService();

            // Stop duration monitoring
            if (durationMonitor != null) {
                try {
                    durationMonitor.stopMonitoring();
                    durationMonitor.resetDuration();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping duration monitor during cleanup", e);
                }
            }



            // Reset all recording state flags
            isRecording = false;
            isSegmentRollingEnabled = false;
            currentRecordingPath = null;

            Log.d(TAG, "Recording state cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
            // Force reset state even if cleanup partially failed
            isRecording = false;
            isSegmentRollingEnabled = false;
            currentRecordingPath = null;
        }
    }

    /**
     * Create quick file info for fast stop recording response without expensive metadata operations
     */
    private JSObject createQuickFileInfo(String filePath) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info only - no expensive MediaMetadataRetriever operations
            info.put("uri", "file://" + filePath);
            info.put("path", filePath);
            info.put("name", file.getName());
            info.put("size", file.length());
            info.put("exists", file.exists());
            info.put("mimeType", "audio/mp4"); // Default for M4A files
            info.put("duration", 0); // Set to 0 for quick response, can be calculated later if needed

            // Use the current recording config for quick response
            if (recordingConfig != null) {
                info.put("sampleRate", recordingConfig.getSampleRate());
                info.put("channels", recordingConfig.getChannels());
                info.put("bitrate", recordingConfig.getBitrate());
            } else {
                // Fallback to defaults
                info.put("sampleRate", 22050);
                info.put("channels", 1);
                info.put("bitrate", 64000);
            }

            Log.d(TAG, "Created quick file info for: " + file.getName() + " (size: " + file.length() + " bytes)");

        } catch (Exception e) {
            Log.e(TAG, "Error creating quick file info", e);
            info.put("error", e.getMessage());
        }

        return info;
    }

    /**
     * Create file info with actual duration calculation for stop recording response
     */
    private JSObject createFileInfoWithDuration(String filePath) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info
            info.put("uri", "file://" + filePath);
            info.put("path", filePath);
            info.put("name", file.getName());
            info.put("size", file.length());
            info.put("exists", file.exists());
            info.put("mimeType", "audio/mp4"); // Default for M4A files

            // Calculate actual duration using AudioFileProcessor
            double actualDuration = AudioFileProcessor.getAudioDuration(filePath);
            info.put("duration", actualDuration);

            Log.d(TAG, "Calculated duration: " + actualDuration + " seconds for file: " + file.getName());

            // Use the current recording config
            if (recordingConfig != null) {
                info.put("sampleRate", recordingConfig.getSampleRate());
                info.put("channels", recordingConfig.getChannels());
                info.put("bitrate", recordingConfig.getBitrate());
            } else {
                // Fallback to defaults
                info.put("sampleRate", 22050);
                info.put("channels", 1);
                info.put("bitrate", 64000);
            }

            // Add standard fields for compatibility
            info.put("createdAt", System.currentTimeMillis());
            info.put("filename", file.getName());
            info.put("base64", ""); // Android doesn't include base64 by default for performance

            Log.d(TAG, "Created file info with duration: " + actualDuration + "s for: " + file.getName() +
                  " (size: " + file.length() + " bytes)");

        } catch (Exception e) {
            Log.e(TAG, "Error creating file info with duration", e);
            info.put("error", e.getMessage());
            info.put("duration", 0); // Fallback to 0 on error
        }

        return info;
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

            // Get preloadNext option (default to true for backwards compatibility)
            boolean preloadNext = call.getBoolean("preloadNext", true);

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

            if (permissionManager != null) {
                permissionManager = null;
            }

            if (eventManager != null) {
                eventManager = null;
            }

            if (durationMonitor != null) {
                durationMonitor.stopMonitoring();
                durationMonitor = null;
            }

            if (fileManager != null) {
                fileManager = null;
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

    // MARK: - AudioRecordingService.RecordingServiceListener Implementation

    @Override
    public void onScreenLocked() {
        Log.d(TAG, "Screen locked - pausing duration counter but maintaining recording");

        // Pause duration monitoring (timer continues but duration doesn't increment)
        if (durationMonitor != null) {
            durationMonitor.pauseDuration();
        }

        // Log for debugging
        Log.d(TAG, "Duration monitoring paused during screen lock");
    }

    @Override
    public void onScreenUnlocked() {
        Log.d(TAG, "Screen unlocked - resuming duration counter");

        // Resume duration monitoring
        if (durationMonitor != null) {
            durationMonitor.resumeDuration();
        }

        // Log for debugging
        Log.d(TAG, "Duration monitoring resumed after screen unlock");
    }

    @Override
    public void onRecordingStateChanged(boolean isRecording) {
        Log.d(TAG, "Recording service state changed: " + (isRecording ? "started" : "stopped"));

        // Could emit events to frontend if needed
        if (eventManager != null) {
            JSObject eventData = new JSObject();
            eventData.put("serviceState", isRecording ? "started" : "stopped");
            eventManager.emitRecordingStateChange("service", eventData);
        }
    }

}
