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

    // Simple recording state
    private boolean isRecording = false;
    private AudioRecordingConfig recordingConfig;
    private MediaRecorderWrapper mediaRecorder;
    private File currentRecordingFile;
    private DurationMonitor durationMonitor;

    // Rolling segments manager (Android-only enhancement)
    private RollingRecordingManager rollingRecordingManager;
    private Integer originalMaxDuration; // Store original maxDuration for resume after reset

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
        // Initialize duration monitor for simple recording
        durationMonitor = new DurationMonitor(mainHandler, new DurationMonitor.DurationCallback() {
            @Override
            public void onDurationChanged(double duration) {
                if (eventManager != null) {
                    eventManager.emitDurationChange(duration);
                }
            }

            @Override
            public void onMaxDurationReached() {
                // Auto-stop recording when max duration is reached
                try {
                    JSObject result = stopRecordingInternal();
                    // Emit recording completed event
                    if (eventManager != null) {
                        JSObject eventData = new JSObject();
                        eventData.put("filePath", result.getString("filePath"));
                        eventData.put("duration", result.getDouble("duration"));
                        eventManager.emitRecordingStateChange("completed", eventData);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to auto-stop recording at max duration", e);
                    JSObject errorData = AudioEngineError.OPERATION_TIMEOUT.toJSObject("Recording reached maximum duration");
                    if (eventManager != null) {
                        eventManager.emitError(errorData);
                    }
                }
            }
        });
        Log.d(TAG, "Duration monitor initialized for simple recording");
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

    public void requestPermission(String alias, PluginCall call, String callbackMethod) {
        requestPermissionForAlias(alias, call, callbackMethod);
    }

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

        // Validate state - check if recording is already active
        if (isRecording) {
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

        // Get maxDuration (optional parameter)
        Integer maxDuration = call.getInt("maxDuration"); // Can be null
        Log.d(TAG, "Received maxDuration from call: " + maxDuration);

        recordingConfig = new AudioRecordingConfig.Builder()
            .sampleRate(sampleRate)
            .channels(channels)
            .bitrate(bitrate)
            .build();

        Log.d(TAG, "Using config - SampleRate: " + sampleRate +
              ", Channels: " + channels + ", Bitrate: " + bitrate);

        ValidationUtils.validateAudioParameters(sampleRate, channels, bitrate);

        if (maxDuration != null && maxDuration > 0) {
            Log.d(TAG, "Starting rolling segment recording with maxDuration: " + maxDuration + " seconds");
            // Store original maxDuration for potential resume after reset
            originalMaxDuration = maxDuration;
            try {
                // Initialize and start rolling segments
                rollingRecordingManager = new RollingRecordingManager(getContext(), fileManager, recordingConfig, maxDuration);
                rollingRecordingManager.start();

                // Start duration monitoring and service
                if (durationMonitor != null) {
                    durationMonitor.resetDuration();
                    durationMonitor.startMonitoring();
                }
                startRecordingService();

                // Wave levels
                if (waveLevelEmitter != null) {
                    waveLevelEmitter.startMonitoring();
                    Log.d(TAG, "Wave level monitoring started (rolling)");
                }

                isRecording = true;
                JSObject stateData = new JSObject();
                eventManager.emitRecordingStateChange("recording", stateData);
            } catch (Exception e) {
                Log.e(TAG, "Failed to start rolling recording", e);
                cleanupRecordingState();
                throw e;
            }
        } else {
            Log.d(TAG, "Starting simple single-file recording without duration limit");
            // Clear maxDuration for simple recording
            originalMaxDuration = null;
            // Start simple single-file recording
            try {
                startSimpleRecording();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start simple recording", e);
                cleanupRecordingState();
                throw e;
            }
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
        // Ensure there is an active recording session to reset
        if (!isRecording) {
            call.reject("No active recording session to reset");
            return;
        }

        try {
            // Pause recording first
            pauseRecordingInternal(null);

            Log.d(TAG, "Resetting recording session - discarding current recording data");

            // Clean up recording files and reset managers
            if (rollingRecordingManager != null && rollingRecordingManager.isActive()) {
                // For rolling recording, stop and clean up files but keep manager for resume
                try {
                    // Stop current recording
                    rollingRecordingManager.cleanup();
                    // Clean up segment files and merged files
                    cleanUpRecordingFiles();
                    Log.d(TAG, "Rolling recording files cleaned up");
                } catch (Exception e) {
                    Log.w(TAG, "Error cleaning up rolling recording files", e);
                }
                // Keep rollingRecordingManager = null for now, will be recreated on resume
                rollingRecordingManager = null;
            } else if (mediaRecorder != null) {
                // For simple recording, stop and delete current file
                if (mediaRecorder.isRecording()) {
                    mediaRecorder.stopSafely();
                }
                // Delete the current recording file since we're resetting
                if (mediaRecorder.getCurrentOutputFile() != null && mediaRecorder.getCurrentOutputFile().exists()) {
                    boolean deleted = mediaRecorder.getCurrentOutputFile().delete();
                    Log.d(TAG, "Current recording file deleted: " + deleted);
                }
                mediaRecorder.release();
                mediaRecorder = null;
                Log.d(TAG, "Simple recording stopped and discarded");
            }

            // Reset duration monitor (resetDuration already handles stopping and resetting)
            if (durationMonitor != null) {
                durationMonitor.resetDuration();
                Log.d(TAG, "Duration monitor reset to 0");
            }

            // Stop waveform monitoring to clear UI data
            if (waveLevelEmitter != null) {
                waveLevelEmitter.stopMonitoring();
                Log.d(TAG, "Wave level monitoring stopped for reset");
            }

            // Keep isRecording = true to maintain session state
            // Reset to paused state so user can resume or start fresh
            JSObject resetData = new JSObject();
            resetData.put("duration", 0.0);
            resetData.put("isRecording", true); // Session remains active and resumable
            resetData.put("status", "paused");
            eventManager.emitRecordingStateChange("paused", resetData);

            Log.d(TAG, "Recording session reset successfully - ready for new recording");
            call.resolve();

        } catch (Exception e) {
            Log.e(TAG, "Failed to reset recording", e);
            call.reject("Failed to reset recording: " + e.getMessage());
        }
    }

    /**
     * Clean up recording files (segments and merged files)
     */
    private void cleanUpRecordingFiles() {
        try {
            File recordingsDir = fileManager.getRecordingsDirectory();
            File segmentsDir = new File(recordingsDir, "segments");

            if (segmentsDir.exists()) {
                File[] segmentFiles = segmentsDir.listFiles();
                if (segmentFiles != null) {
                    for (File file : segmentFiles) {
                        if (file.isFile() && (file.getName().endsWith(".m4a") || file.getName().startsWith("seg_") || file.getName().startsWith("rollingMerged"))) {
                            boolean deleted = file.delete();
                            Log.d(TAG, "Deleted recording file: " + file.getName() + " - " + deleted);
                        }
                    }
                }
            }

            // Also clean up any temporary files in the main recordings directory
            File[] recordingFiles = recordingsDir.listFiles();
            if (recordingFiles != null) {
                for (File file : recordingFiles) {
                    if (file.isFile() && (file.getName().startsWith("recording_") || file.getName().startsWith("temp_"))) {
                        boolean deleted = file.delete();
                        Log.d(TAG, "Deleted temp recording file: " + file.getName() + " - " + deleted);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error cleaning up recording files", e);
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
        long stopStartNs = System.nanoTime();
        // Check if any recording is active
        if (!isRecording) {
            throw new IllegalStateException("No active recording to stop");
        }

        String fileToReturn;

        if (rollingRecordingManager != null && rollingRecordingManager.isActive()) {
            Log.d(TAG, "Stopping rolling recording and assembling final...");
            long assembleStartNs = System.nanoTime();
            File finalFile = rollingRecordingManager.stopAndAssembleFinal();
            long assembleEndNs = System.nanoTime();
            Log.i(TAG, "[Stop] Rolling assembly time: " + ((assembleEndNs - assembleStartNs) / 1_000_000) + " ms");
            fileToReturn = finalFile.getAbsolutePath();
            stopRecordingService();
            Log.d(TAG, "Rolling recording stopped -> " + finalFile.getName());
        } else {
            Log.d(TAG, "Stopping simple recording...");
            // Stop simple recording
            if (mediaRecorder != null && mediaRecorder.isRecording()) {
                long simpleStopStartNs = System.nanoTime();
                File recordingFile = mediaRecorder.stopSafely();
                long simpleStopEndNs = System.nanoTime();
                Log.i(TAG, "[Stop] Simple stop time: " + ((simpleStopEndNs - simpleStopStartNs) / 1_000_000) + " ms");

                if (recordingFile == null || !recordingFile.exists()) {
                    Log.e(TAG, "Recording file is null or doesn't exist");
                    throw new IllegalStateException("No valid recording file found to return");
                }

                fileToReturn = recordingFile.getAbsolutePath();

                // Stop recording service as recording is complete
                stopRecordingService();

                Log.d(TAG, "Simple recording stopped: " + recordingFile.getName());
            } else {
                Log.e(TAG, "Media recorder is null or not recording!");
                throw new IllegalStateException("Media recorder not initialized or not recording");
            }
        }

            // Stop wave level monitoring
        if (waveLevelEmitter != null) {
            waveLevelEmitter.stopMonitoring();
            Log.d(TAG, "Wave level monitoring stopped");
        }

        // Stop and reset duration monitoring
        if (durationMonitor != null) {
            try {
                durationMonitor.stopMonitoring();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping duration monitor on stop", e);
            }
            durationMonitor.resetDuration();
        }

        isRecording = false;
        rollingRecordingManager = null;

        // Get file info and return - with actual duration calculation
        long infoStartNs = System.nanoTime();
        JSObject response = createFileInfoWithDuration(fileToReturn);
        long infoEndNs = System.nanoTime();
        Log.i(TAG, "[Stop] File info time: " + ((infoEndNs - infoStartNs) / 1_000_000) + " ms");
        long stopEndNs = System.nanoTime();
        Log.i(TAG, "[Stop] Total stop processing time: " + ((stopEndNs - stopStartNs) / 1_000_000) + " ms");
        Log.d(TAG, "Recording stopped - File: " + new File(fileToReturn).getName());

        return response;
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        if (isRecording) {
            JSObject result = new JSObject();

            if (durationMonitor != null) {
                // Use the duration monitor's current duration
                double currentDuration = durationMonitor.getCurrentDuration();
                result.put("duration", currentDuration);
                Log.d(TAG, "Simple recording current duration: " + currentDuration + " seconds");
            } else {
                // If duration monitor is null, return 0
                result.put("duration", 0.0);
                Log.d(TAG, "Duration monitor not available - duration: 0");
            }

            call.resolve(result);
        } else {
            call.reject("No active recording");
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        String status;

        if (isRecording) {
            status = "recording";
        } else {
            status = "idle";
        }

        JSObject result = new JSObject();
        result.put("status", status);
        result.put("isRecording", isRecording);

        if (durationMonitor != null) {
            // Use the duration monitor's current duration
            double currentDuration = durationMonitor.getCurrentDuration();
            result.put("duration", currentDuration);
        } else {
            result.put("duration", 0.0);
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
            boolean appRecording = isRecording;
            Log.d(TAG, "App currently recording: " + appRecording + " (isRecording: " + isRecording + ")");

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

    private void startSimpleRecording() throws IOException {
        // Create output file
        File outputDirectory = fileManager.getRecordingsDirectory();
        String filename = "recording_" + System.currentTimeMillis() + ".m4a";
        currentRecordingFile = new File(outputDirectory, filename);

        Log.d(TAG, "Starting simple recording to file: " + currentRecordingFile.getName());

        // Initialize media recorder
        mediaRecorder = new MediaRecorderWrapper();
        mediaRecorder.configureAndStart(recordingConfig, currentRecordingFile);

        // Start duration monitoring
        if (durationMonitor != null) {
            durationMonitor.resetDuration();
            durationMonitor.startMonitoring();
        }

        // Start foreground service for background recording
        startRecordingService();

        // Start wave level monitoring for real-time audio levels
        if (waveLevelEmitter != null) {
            waveLevelEmitter.startMonitoring();
            Log.d(TAG, "Wave level monitoring started");
        }

        isRecording = true;

        JSObject stateData = new JSObject();
        eventManager.emitRecordingStateChange("recording", stateData);
    }

    private void cleanupRecordingState() {
        try {
            Log.d(TAG, "Cleaning up recording state...");

            // Stop and release media recorder
            if (mediaRecorder != null) {
                try {
                    if (mediaRecorder.isRecording()) {
                        mediaRecorder.stopSafely();
                    }
                    mediaRecorder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing media recorder during cleanup", e);
                }
                mediaRecorder = null;
            }

            // Stop duration monitoring
            if (durationMonitor != null) {
                try {
                    durationMonitor.stopMonitoring();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping duration monitor", e);
                }
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
            // Simple recording mode

            Log.d(TAG, "Recording state cleaned up successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup", e);
            // Force reset state even if cleanup partially failed
            isRecording = false;
            // Simple recording mode
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
        try {
            // Finalize current work and move to paused state
            if (rollingRecordingManager != null && isRecording) {
                rollingRecordingManager.pauseForInterruption();
            } else if (mediaRecorder != null && mediaRecorder.isRecording()) {
                // For simple mode, finalize current file and keep path
                File file = mediaRecorder.stopSafely();
                if (file != null && file.exists()) {
                    currentRecordingFile = file;
                }
            }

            if (durationMonitor != null) {
                durationMonitor.pauseDuration();
            }
            if (waveLevelEmitter != null) {
                waveLevelEmitter.pauseMonitoring();
            }

            JSObject errorData = new JSObject();
            errorData.put("message", "Recording paused due to interruption: " + type);
            errorData.put("code", type.name());
            eventManager.emitError(errorData);

            JSObject pauseData = new JSObject();
            pauseData.put("status", "paused");
            pauseData.put("isRecording", true);
            pauseData.put("reason", type.name());
            eventManager.emitRecordingStateChange("paused", pauseData);
        } catch (Exception e) {
            Log.w(TAG, "Error handling interruption begin", e);
        }
    }

    @Override
    public void onInterruptionEnded(AudioInterruptionManager.InterruptionType type, boolean shouldResume) {
        Log.d(TAG, "Audio interruption ended: " + type + ", should resume: " + shouldResume);
        if (!isRecording) return;
        if (!shouldResume) return;
        try {
            if (rollingRecordingManager != null) {
                rollingRecordingManager.resumeAfterInterruption();
            } else {
                // Simple mode: start a fresh file to continue
                startSimpleRecording();
            }

            if (durationMonitor != null) {
                durationMonitor.resumeDuration();
            }
            if (waveLevelEmitter != null) {
                try {
                    if (!waveLevelEmitter.isMonitoring()) waveLevelEmitter.startMonitoring();
                    else waveLevelEmitter.resumeMonitoring();
                } catch (Exception ignored) {}
            }

            JSObject resumeData = new JSObject();
            resumeData.put("status", "recording");
            resumeData.put("isRecording", true);
            eventManager.emitRecordingStateChange("recording", resumeData);
        } catch (Exception e) {
            Log.w(TAG, "Error resuming after interruption", e);
        }
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
        if (!isRecording) {
            if (call != null) call.reject("No active recording session to pause");
            return;
        }

        try {
            // Pause media recorder (if supported)
            if (mediaRecorder != null && mediaRecorder.isRecording()) {
                // Note: Standard MediaRecorder doesn't support pause/resume
                // For now, we'll just pause the duration monitor and wave level monitoring
                Log.d(TAG, "Pausing recording (duration and wave monitoring)");
            }

            // Pause duration monitoring
            if (durationMonitor != null) {
                durationMonitor.pauseDuration();
            }

            // Pause wave level monitoring
            if (waveLevelEmitter != null) {
                waveLevelEmitter.pauseMonitoring();
                Log.d(TAG, "Wave level monitoring paused");
            }

            // Emit pause state change event
            if (eventManager != null) {
                JSObject pauseData = new JSObject();
                if (durationMonitor != null) {
                    pauseData.put("duration", durationMonitor.getCurrentDuration());
                } else {
                    pauseData.put("duration", 0.0);
                }
                pauseData.put("isRecording", true); // Session is still active
                pauseData.put("status", "paused");
                eventManager.emitRecordingStateChange("paused", pauseData);
            }

            if (call != null) call.resolve();
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
            // Check if recording infrastructure was reset (both managers are null)
            if (mediaRecorder == null && rollingRecordingManager == null) {
                Log.d(TAG, "Recording infrastructure was reset, restarting recording...");

                // Restart recording based on the original configuration
                if (recordingConfig != null) {
                    if (originalMaxDuration != null && originalMaxDuration > 0) {
                        // Restart rolling recording with original maxDuration
                        Log.d(TAG, "Restarting rolling recording after reset with maxDuration: " + originalMaxDuration);
                        try {
                            rollingRecordingManager = new RollingRecordingManager(getContext(), fileManager, recordingConfig, originalMaxDuration);
                            rollingRecordingManager.start();

                            // Start duration monitoring and service
                            if (durationMonitor != null) {
                                durationMonitor.resetDuration();
                                durationMonitor.startMonitoring();
                            }
                            startRecordingService();

                            // Wave levels
                            if (waveLevelEmitter != null) {
                                waveLevelEmitter.startMonitoring();
                                Log.d(TAG, "Wave level monitoring started (rolling)");
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to restart rolling recording after reset", e);
                            if (call != null) call.reject("Failed to restart rolling recording: " + e.getMessage());
                            return;
                        }
                    } else {
                        // Restart simple recording
                        Log.d(TAG, "Restarting simple recording after reset");
                        startSimpleRecording();
                    }
                } else {
                    Log.e(TAG, "Cannot resume recording - no recording configuration available");
                    if (call != null) call.reject("Cannot resume recording - no configuration available");
                    return;
                }
            } else {
                // Normal resume - just resume monitoring
                Log.d(TAG, "Resuming existing recording (duration and wave monitoring)");

                // Resume duration monitoring
                if (durationMonitor != null) {
                    durationMonitor.resumeDuration();
                }

                // Resume wave level monitoring
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
            }

            // Emit recording state change event
            if (eventManager != null) {
                JSObject resumeData = new JSObject();
                if (durationMonitor != null) {
                    resumeData.put("duration", durationMonitor.getCurrentDuration());
                } else {
                    resumeData.put("duration", 0.0);
                }
                resumeData.put("isRecording", true);
                resumeData.put("status", "recording");
                eventManager.emitRecordingStateChange("recording", resumeData);
            }

            if (call != null) call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume recording", e);
            if (call != null) call.reject("Failed to resume recording: " + e.getMessage());
        }
    }
}
