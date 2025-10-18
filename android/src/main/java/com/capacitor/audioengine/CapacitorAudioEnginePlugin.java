package com.capacitor.audioengine;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import java.io.File;
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
public class CapacitorAudioEnginePlugin extends Plugin implements EventManager.EventCallback {
    private static final String TAG = "CapacitorAudioEngine";

    // Core managers
    private PermissionManagerService permissionService; // Standalone permission service
    private EventManager eventManager;

    private FileDirectoryManager fileManager;

    private WaveLevelEmitter waveLevelEmitter;

    private RecordingManager recordingManager;
    private PlaybackManager playbackManager;
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
                boolean hasActivity = getActivity() != null;
                boolean shouldShow = hasActivity && ActivityCompat.shouldShowRequestPermissionRationale(getActivity(), permission);
                Log.d(TAG, "shouldShowRequestPermissionRationale - permission: " + permission +
                           ", hasActivity: " + hasActivity + ", shouldShow: " + shouldShow);
                return shouldShow;
            }
        });
        eventManager = new EventManager(this);
        fileManager = new FileDirectoryManager(getContext());

        // Initialize wave level emitter with event manager callback
        waveLevelEmitter = new WaveLevelEmitter(eventManager);

        // Initialize recording manager
        recordingManager = new RecordingManager(getContext(), new RecordingManager.RecordingCallback() {
            @Override public void onStatusChanged(String status) {
                JSObject data = new JSObject();
                data.put("status", status);
                notifyListeners("recordingStatusChanged", data);
            }
            @Override public void onError(String message) {
                JSObject data = new JSObject();
                data.put("message", message);
                notifyListeners("error", data);
            }

            @Override public void onDurationChanged(double duration) {
                JSObject data = new JSObject();
                data.put("duration", duration);
                notifyListeners("durationChange", data);
            }

            public void onWaveLevel(double level, long timestamp) {
                JSObject data = new JSObject();
                data.put("level", level);
                data.put("timestamp", timestamp);
                notifyListeners("waveLevel", data);
            }
        });

        // Initialize playback manager
        playbackManager = new PlaybackManager(getContext(), new PlaybackManager.PlaybackCallback() {
            @Override public void onStatusChanged(String status, String url, int position) {
                JSObject data = new JSObject();
                data.put("status", status);
                data.put("url", url);
                data.put("position", position);
                notifyListeners("playbackStatusChanged", data);
            }

            @Override public void onError(String trackId, String message) {
                JSObject data = new JSObject();
                data.put("trackId", trackId);
                data.put("message", message);
                notifyListeners("playbackError", data);
            }

            @Override public void onProgress(String trackId, String url, int currentPosition, int duration, boolean isPlaying) {
                JSObject data = new JSObject();
                data.put("trackId", trackId);
                data.put("url", url);
                data.put("currentPosition", currentPosition);
                data.put("duration", duration);
                data.put("isPlaying", isPlaying);
                notifyListeners("playbackProgress", data);
            }

            @Override public void onTrackCompleted(String trackId, String url) {
                JSObject data = new JSObject();
                data.put("trackId", trackId);
                data.put("url", url);
                notifyListeners("playbackCompleted", data);
            }
        });

        Log.d(TAG, "CapacitorAudioEngine plugin loaded");
    }

    @PluginMethod
    public void checkPermissions(PluginCall call) {
        try {
            JSObject result = permissionService.checkPermissions();
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error checking permissions", e);
            call.reject("Failed to check permissions: " + e.getMessage());
        }
    }

    @PluginMethod
    public void checkPermissionMicrophone(PluginCall call) {
        try {
            JSObject result = permissionService.checkPermissions();
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error checking microphone permission", e);
            call.reject("Failed to check microphone permission: " + e.getMessage());
        }
    }

    @PluginMethod
    public void checkPermissionNotifications(PluginCall call) {
        try {
            JSObject result = permissionService.checkPermissions();
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error checking notification permission", e);
            call.reject("Failed to check notification permission: " + e.getMessage());
        }
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        try {
            permissionService.requestPermissions(call);
        } catch (Exception e) {
            Log.e(TAG, "Error requesting permissions", e);
            call.reject("Failed to request permissions: " + e.getMessage());
        }
    }

    public void requestPermission(String alias, PluginCall call, String callbackMethod) {
        Log.d(TAG, "requestPermission called - alias: " + alias + ", callbackMethod: " + callbackMethod);
        Log.d(TAG, "Activity available: " + (getActivity() != null));
        Log.d(TAG, "Context available: " + (getContext() != null));
        Log.d(TAG, "Current thread: " + Thread.currentThread().getName());
        Log.d(TAG, "Is main thread: " + (Looper.myLooper() == Looper.getMainLooper()));

        try {
            // Check if we have an activity context
            if (getActivity() == null) {
                Log.e(TAG, "No activity available for permission request");
                call.reject("No activity available for permission request");
                return;
            }

            // Ensure we're on the main thread for permission requests
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.d(TAG, "Switching to main thread for permission request");
                mainHandler.post(() -> {
                    try {
                        requestPermissionForAlias(alias, call, callbackMethod);
                        Log.d(TAG, "requestPermissionForAlias completed successfully on main thread");
                    } catch (Exception e) {
                        Log.e(TAG, "Error in requestPermissionForAlias on main thread", e);
                        call.reject("Permission request failed: " + e.getMessage());
                    }
                });
            } else {
                requestPermissionForAlias(alias, call, callbackMethod);
                Log.d(TAG, "requestPermissionForAlias completed successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in requestPermissionForAlias", e);
            call.reject("Permission request failed: " + e.getMessage());
        }
    }

    @Override
    public void notifyListeners(String eventName, JSObject data) {
        super.notifyListeners(eventName, data);
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

            Double startTime = call.getDouble("startTime");
            Double endTime = call.getDouble("endTime");

            if (startTime == null || endTime == null) {
                call.reject(AudioEngineError.INVALID_PARAMETERS.getCode(),
                           "Missing required parameters: startTime and endTime");
                return;
            }

            Log.d(TAG, "trimAudio called with URI: " + sourcePath + ", startTime: " + startTime + ", endTime: " + endTime);

            // Handle legacy URI formats (simplified for legacy compatibility)
            String actualPath = sourcePath;
            if (sourcePath.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = sourcePath.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (sourcePath.startsWith("file://")) {
                actualPath = sourcePath.substring(7);
            }

            Log.d(TAG, "Resolved URI to file path: " + actualPath);

            // Validate file exists and is accessible
            File sourceFile = new File(actualPath);
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source file does not exist: " + actualPath);
                call.reject(AudioEngineError.INVALID_FILE_PATH.getCode(),
                           "File does not exist: " + actualPath + ". Please ensure the recording was stopped successfully before trimming.");
                return;
            }

            if (sourceFile.length() == 0) {
                Log.e(TAG, "Source file is empty: " + actualPath);
                call.reject(AudioEngineError.INVALID_FILE_PATH.getCode(),
                           "File is empty: " + actualPath + ". The recording may not have completed properly.");
                return;
            }

            Log.d(TAG, "Source file validated - exists: true, size: " + sourceFile.length() + " bytes");

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

            Log.d(TAG, "Trimming audio from " + startTime + "s to " + actualEndTime + "s (original endTime: " + endTime + "s, duration: " + audioDuration + "s)");

            // Create output file in the same directory as the source file
            File sourceDirectory = sourceFile.getParentFile();
            String originalFileName = sourceFile.getName();
            // Create temporary name first to avoid conflicts during processing
            String tempOutputFileName = "temp_trimming_" + System.currentTimeMillis() + ".m4a";
            File tempOutputFile = new File(sourceDirectory, tempOutputFileName);

            // Perform trimming using AudioFileProcessor with clamped end time
            AudioFileProcessor.trimAudioFile(sourceFile, tempOutputFile, startTime, actualEndTime);

            // Delete the original file
            try {
                if (sourceFile.delete()) {
                    Log.d(TAG, "Deleted original file: " + sourceFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Warning: Could not delete original file: " + sourceFile.getAbsolutePath());
                }
            } catch (Exception deleteError) {
                Log.w(TAG, "Warning: Exception deleting original file: " + deleteError.getMessage());
            }

            // Rename the temporary trimmed file to the original filename
            File finalOutputFile = new File(sourceDirectory, originalFileName);
            try {
                if (tempOutputFile.renameTo(finalOutputFile)) {
                    Log.d(TAG, "Renamed trimmed file to original name: " + finalOutputFile.getAbsolutePath());
                } else {
                    Log.w(TAG, "Warning: Could not rename trimmed file, using temp name");
                    finalOutputFile = tempOutputFile; // Use temp file if rename fails
                }
            } catch (Exception renameError) {
                Log.w(TAG, "Warning: Exception renaming trimmed file: " + renameError.getMessage());
                finalOutputFile = tempOutputFile; // Use temp file if rename fails
            }

            // Get complete audio file info
            JSObject audioInfo = AudioFileProcessor.getAudioFileInfo(finalOutputFile.getAbsolutePath());
            call.resolve(audioInfo);

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

            JSObject response = AudioFileProcessor.getAudioFileInfo(actualPath);
            call.resolve(response);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio info", e);
            call.reject("AUDIO_INFO_ERROR", "Failed to get audio info: " + e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void configureWaveform(PluginCall call) {
        try {
            int intervalMs = call.getInt("EmissionInterval", 200);

            if (waveLevelEmitter != null) {
                // Configure sample rate to match recording configuration
                waveLevelEmitter.setSampleRate(AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE);

                // Configure emission interval
                waveLevelEmitter.setEmissionInterval(intervalMs);

                Log.d(TAG, "Wave level emitter configured - interval: " + intervalMs + "ms.");

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

    // ==================== PLAYBACK METHODS ====================
    @PluginMethod
    public void preloadTracks(PluginCall call) {
        try {
            JSArray tracksArray = call.getArray("tracks");
            if (tracksArray == null) {
                call.reject("INVALID_PARAMETERS", "Missing required parameter: tracks");
                return;
            }

            List<String> tracks = new ArrayList<>();
            for (int i = 0; i < tracksArray.length(); i++) {
                String url = tracksArray.getString(i);
                if (url != null && !url.isEmpty()) {
                    tracks.add(url);
                }
            }

            if (tracks.isEmpty()) {
                call.reject("INVALID_PARAMETERS", "No valid tracks provided");
                return;
            }

            // Preload all tracks
            JSArray results = new JSArray();
            int[] completedCount = {0};
            int totalTracks = tracks.size();

            for (String url : tracks) {
                playbackManager.preloadTrack(url, new PlaybackManager.PreloadCallback() {
                    @Override
                    public void onSuccess(String trackUrl, String mimeType, int duration, long size) {
                        JSObject trackResult = new JSObject();
                        trackResult.put("url", trackUrl);
                        trackResult.put("loaded", true);
                        trackResult.put("mimeType", mimeType);
                        trackResult.put("duration", duration);
                        trackResult.put("size", size);
                        results.put(trackResult);

                        completedCount[0]++;
                        if (completedCount[0] == totalTracks) {
                            JSObject response = new JSObject();
                            response.put("tracks", results);
                            call.resolve(response);
                        }
                    }

                    @Override
                    public void onError(String trackUrl, String message) {
                        JSObject trackResult = new JSObject();
                        trackResult.put("url", trackUrl);
                        trackResult.put("loaded", false);
                        trackResult.put("error", message);
                        results.put(trackResult);

                        completedCount[0]++;
                        if (completedCount[0] == totalTracks) {
                            JSObject response = new JSObject();
                            response.put("tracks", results);
                            call.resolve(response);
                        }
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Error preloading tracks", e);
            call.reject("PRELOAD_ERROR", "Failed to preload tracks: " + e.getMessage());
        }
    }

    @PluginMethod
    public void playTrack(PluginCall call) {
        try {
            String url = call.getString("url");
            playbackManager.playTrack(url);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error playing track", e);
            call.reject("PLAY_ERROR", "Failed to play track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pauseTrack(PluginCall call) {
        try {
            String url = call.getString("url");
            playbackManager.pauseTrack(url);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error pausing track", e);
            call.reject("PAUSE_ERROR", "Failed to pause track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void resumeTrack(PluginCall call) {
        try {
            String url = call.getString("url");
            playbackManager.resumeTrack(url);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error resuming track", e);
            call.reject("RESUME_ERROR", "Failed to resume track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopTrack(PluginCall call) {
        try {
            String url = call.getString("url");
            playbackManager.stopTrack(url);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping track", e);
            call.reject("STOP_ERROR", "Failed to stop track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void seekTrack(PluginCall call) {
        try {
            Integer seconds = call.getInt("seconds");
            if (seconds == null) {
                call.reject("INVALID_PARAMETERS", "Missing required parameter: seconds");
                return;
            }

            String url = call.getString("url");
            playbackManager.seekTrack(seconds, url);
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error seeking track", e);
            call.reject("SEEK_ERROR", "Failed to seek track: " + e.getMessage());
        }
    }

    @PluginMethod
    public void skipToNext(PluginCall call) {
        // Simplified - no-op for single track playback
        call.resolve();
    }

    @PluginMethod
    public void skipToPrevious(PluginCall call) {
        // Simplified - no-op for single track playback
        call.resolve();
    }

    @PluginMethod
    public void skipToIndex(PluginCall call) {
        // Simplified - no-op for single track playback
        call.resolve();
    }

    @PluginMethod
    public void getPlaybackInfo(PluginCall call) {
        try {
            PlaybackManager.PlaybackInfo info = playbackManager.getPlaybackInfo();

            JSObject result = new JSObject();

            if (info.trackId != null && info.url != null) {
                JSObject currentTrack = new JSObject();
                currentTrack.put("id", info.trackId);
                currentTrack.put("url", info.url);
                result.put("currentTrack", currentTrack);
            } else {
                result.put("currentTrack", JSObject.NULL);
            }

            result.put("currentIndex", info.currentIndex);
            result.put("currentPosition", info.currentPosition);
            result.put("duration", info.duration);
            result.put("isPlaying", info.isPlaying);

            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Error getting playback info", e);
            call.reject("PLAYBACK_INFO_ERROR", "Failed to get playback info: " + e.getMessage());
        }
    }

    @PluginMethod
    public void destroyPlayback(PluginCall call) {
        try {
            if (playbackManager != null) {
                playbackManager.destroy();
                // Reinitialize with same callback
                playbackManager = new PlaybackManager(getContext(), new PlaybackManager.PlaybackCallback() {
                    @Override public void onStatusChanged(String status, String url, int position) {
                        JSObject data = new JSObject();
                        data.put("status", status);
                        data.put("url", url);
                        data.put("position", position);
                        notifyListeners("playbackStatusChanged", data);
                    }

                    @Override public void onError(String trackId, String message) {
                        JSObject data = new JSObject();
                        data.put("trackId", trackId);
                        data.put("message", message);
                        notifyListeners("playbackError", data);
                    }

                    @Override public void onProgress(String trackId, String url, int currentPosition, int duration, boolean isPlaying) {
                        JSObject data = new JSObject();
                        data.put("trackId", trackId);
                        data.put("url", url);
                        data.put("currentPosition", currentPosition);
                        data.put("duration", duration);
                        data.put("isPlaying", isPlaying);
                        notifyListeners("playbackProgress", data);
                    }

                    @Override public void onTrackCompleted(String trackId, String url) {
                        JSObject data = new JSObject();
                        data.put("trackId", trackId);
                        data.put("url", url);
                        notifyListeners("playbackCompleted", data);
                    }
                });
            }
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error destroying playback", e);
            call.reject("DESTROY_PLAYBACK_ERROR", "Failed to destroy playback: " + e.getMessage());
        }
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            permissionService.openSettings();
            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Error opening app settings", e);
            call.reject("Failed to open app settings: " + e.getMessage());
        }
    }

    @PermissionCallback
    public void permissionCallback(PluginCall call) {
        Log.d(TAG, "Permission callback received");

        try {
            // Check the actual permission status instead of relying on data field
            JSObject currentStatus = permissionService.checkPermissions();
            boolean granted = currentStatus.getBoolean("granted", false);
            Log.d(TAG, "Permission status after callback - granted: " + granted);

            permissionService.handlePermissionCallback(call, granted);
        } catch (Exception e) {
            Log.e(TAG, "Error handling permission callback", e);
            call.reject("Failed to handle permission callback: " + e.getMessage());
        }
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();

        Log.d(TAG, "Plugin destroying - cleaning up all resources");

        try {
            // Stop recording if active
            if (recordingManager != null) {
                try {
                    recordingManager.stopRecording();
                    Log.d(TAG, "Recording stopped during plugin destruction");
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping recording during destruction", e);
                }
                recordingManager = null;
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

            // Clean up playback manager
            if (playbackManager != null) {
                playbackManager.destroy();
                playbackManager = null;
            }

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

    @PluginMethod
    public void startRecording(PluginCall call) {
        try {
            // Validate permissions before starting recording
            try {
                permissionService.validateRecordingPermissions();
            } catch (SecurityException e) {
                Log.e(TAG, "Permission validation failed for streaming", e);
                call.reject(AudioEngineError.PERMISSION_DENIED.getCode(), e.getMessage());
                return;
            }

            String outputPath = call.getString("path");
            RecordingManager.StartOptions opts = new RecordingManager.StartOptions();
            opts.path = outputPath;

            // Start recording with validated permissions
            try {
                recordingManager.startRecording(opts);

                // Start wave level monitoring if configured
                if (waveLevelEmitter != null && !waveLevelEmitter.isMonitoring()) {
                    try {
                        waveLevelEmitter.startMonitoring();
                        Log.d(TAG, "Wave level monitoring started with recording");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to start wave level monitoring", e);
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception when starting recording", e);
                call.reject(AudioEngineError.PERMISSION_DENIED.getCode(), "Microphone permission denied");
                return;
            }

            // Get the recording status to retrieve the file path/URI
            RecordingManager.StatusInfo status = recordingManager.getStatus();

            if (status.path() == null || status.path().isEmpty()) {
                call.reject("RECORDING_START_ERROR", "No recording file path available");
                return;
            }

            // Return the URI
            JSObject result = new JSObject();
            result.put("uri", "file://" + status.path());
            call.resolve(result);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start stream", e);
            call.reject("RECORDING_START_ERROR", e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            // Stop wave level monitoring first
            if (waveLevelEmitter != null && waveLevelEmitter.isMonitoring()) {
                try {
                    waveLevelEmitter.stopMonitoring();
                    Log.d(TAG, "Wave level monitoring stopped with recording");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to stop wave level monitoring", e);
                }
            }

            // Stop recording and wait for file to be ready
            String filePath = recordingManager.stopRecordingAndWaitForFile();

            if (filePath == null || filePath.isEmpty()) {
                call.reject("RECORDING_STOP_ERROR", "No recording file path available");
                return;
            }

            // Verify file exists and has content
            File recordingFile = new File(filePath);
            if (!recordingFile.exists()) {
                call.reject("RECORDING_STOP_ERROR", "Recording file was not created: " + filePath);
                return;
            }

            if (recordingFile.length() == 0) {
                call.reject("RECORDING_STOP_ERROR", "Recording file is empty: " + filePath);
                return;
            }

            Log.d(TAG, "Recording stopped successfully. File: " + filePath + ", size: " + recordingFile.length() + " bytes");

            // Pre-warm recorder for faster subsequent recordings after long sessions
            try {
                recordingManager.preWarmRecorder();
                Log.d(TAG, "MediaRecorder pre-warmed for faster subsequent recordings");
            } catch (Exception e) {
                Log.w(TAG, "Failed to pre-warm MediaRecorder", e);
            }

            // Get audio file info
            JSObject audioInfo = AudioFileProcessor.getAudioFileInfo(filePath);
            call.resolve(audioInfo);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping recording", e);
            call.reject("RECORDING_STOP_ERROR", e.getMessage());
        }
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void pauseRecording(PluginCall call) {
        try {
            recordingManager.pauseRecording();

            // Pause wave level monitoring
            if (waveLevelEmitter != null && waveLevelEmitter.isMonitoring()) {
                try {
                    waveLevelEmitter.pauseMonitoring();
                    Log.d(TAG, "Wave level monitoring paused with recording");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to pause wave level monitoring", e);
                }
            }

            call.resolve();
        } catch (Exception e) {
            call.reject("RECORDING_PAUSE_ERROR", e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void resumeRecording(PluginCall call) {
        try {
            recordingManager.resumeRecording();

            // Resume wave level monitoring
            if (waveLevelEmitter != null) {
                try {
                    waveLevelEmitter.resumeMonitoring();
                    Log.d(TAG, "Wave level monitoring resumed with recording");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to resume wave level monitoring", e);
                }
            }

            call.resolve();
        } catch (Exception e) {
            call.reject("RECORDING_RESUME_ERROR", e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void resetRecording(PluginCall call) {
        try {
            recordingManager.resetRecording();
            call.resolve();
        } catch (Exception e) {
            call.reject("RECORDING_RESET_ERROR", e.getMessage());
        }
    }

    @PluginMethod
    public void getRecordingStatus(PluginCall call) {
        try {
            RecordingManager.StatusInfo status = recordingManager.getStatus();
            JSObject result = new JSObject();
            result.put("status", status.status());
            result.put("duration", status.duration());
            if (status.path() != null) {
                result.put("path", status.path());
            }
            call.resolve(result);
        } catch (Exception e) {
            call.reject("RECORDING_STATUS_ERROR", e.getMessage());
        }
    }

    @PluginMethod
    public void removeAllListeners(PluginCall call) {
        try {
            super.removeAllListeners(call);
        } catch (Exception e) {
            Log.e(TAG, "Error removing all listeners", e);
            call.reject("Failed to remove all listeners: " + e.getMessage());
        }
    }


}