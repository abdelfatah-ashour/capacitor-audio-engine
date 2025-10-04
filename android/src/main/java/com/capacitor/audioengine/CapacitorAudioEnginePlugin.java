package com.capacitor.audioengine;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class CapacitorAudioEnginePlugin extends Plugin implements EventManager.EventCallback, PlaybackManager.PlaybackManagerListener {
    private static final String TAG = "CapacitorAudioEngine";

    // Core managers
    private PermissionManagerService permissionService; // Standalone permission service
    private EventManager eventManager;

    private FileDirectoryManager fileManager;
    private PlaybackManager playbackManager;

    private WaveLevelEmitter waveLevelEmitter;

    private RecordingManager recordingManager;
    private Handler mainHandler;

    // Track URL to ID mapping for simplified interface
    private final Map<String, String> urlToTrackIdMap = new HashMap<>();

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
        playbackManager = new PlaybackManager(getContext(), this);
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
            File sourceFile = new File(actualPath);
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
        JSArray tracksArray = call.getArray("tracks");
        if (tracksArray == null) {
            call.reject("Invalid tracks array - expected array of URLs");
            return;
        }

        List<String> trackUrls = new ArrayList<>();

        try {
            for (int i = 0; i < tracksArray.length(); i++) {
                String url = tracksArray.getString(i);
                if (url == null || url.trim().isEmpty()) {
                    call.reject("Invalid track URL at index " + i);
                    return;
                }
                trackUrls.add(url);
            }
        } catch (Exception e) {
            call.reject("Error parsing track URLs: " + e.getMessage());
            return;
        }

        // Clear previous mappings
        urlToTrackIdMap.clear();

        // Run preloading and waiting on a background thread to avoid blocking main thread
        new Thread(() -> {
            try {
                // Preload tracks using simplified interface
                playbackManager.preloadTracks(trackUrls);

                // Wait for tracks to be prepared (no timeout - waits until all tracks are ready)
                // This runs on background thread so it doesn't block MediaPlayer callbacks
                Map<String, Map<String, Object>> tracksMetadata = playbackManager.waitForTracksMetadata(trackUrls);

                // Create track results for response and populate URL mapping
                List<JSObject> trackResults = new ArrayList<>();
                for (int i = 0; i < trackUrls.size(); i++) {
                    String trackId = "track_" + i;
                    String url = trackUrls.get(i);

                    // Store URL to trackId mapping
                    urlToTrackIdMap.put(url, trackId);

                    JSObject trackInfo = new JSObject();
                    trackInfo.put("url", url);
                    trackInfo.put("loaded", true);

                    // Get metadata from prepared tracks
                    Map<String, Object> metadata = tracksMetadata.get(url);
                    if (metadata != null && (Boolean) metadata.getOrDefault("prepared", false)) {
                        // Track is prepared, get real duration from MediaPlayer
                        int durationMs = (Integer) metadata.getOrDefault("duration", 0);
                        double durationSeconds = durationMs / 1000.0;
                        trackInfo.put("duration", durationSeconds);
                        Log.d(TAG, "Using prepared track duration: " + durationSeconds + "s for " + url);
                    } else {
                        // Track not prepared yet or failed, try to extract from file/URL
                        Log.w(TAG, "Track not prepared, attempting fallback metadata extraction for: " + url);
                        try {
                            JSObject extractedMetadata = extractTrackMetadata(url);
                            Double duration = extractedMetadata.has("duration") ? extractedMetadata.getDouble("duration") : 0.0;
                            trackInfo.put("duration", duration);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to extract metadata for track: " + url, e);
                            trackInfo.put("duration", 0.0);
                        }
                    }

                    // Extract mimeType and size
                    try {
                        JSObject extractedMetadata = extractTrackMetadata(url);
                        String mimeType = extractedMetadata.has("mimeType") ? extractedMetadata.getString("mimeType") : "audio/mpeg";
                        Long size = extractedMetadata.has("size") ? extractedMetadata.getLong("size") : 0L;

                        trackInfo.put("mimeType", mimeType);
                        trackInfo.put("size", size);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to extract metadata for track: " + url, e);
                        trackInfo.put("mimeType", "audio/mpeg");
                        trackInfo.put("size", 0L);
                    }

                    trackResults.add(trackInfo);
                }

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
        }).start();
    }

    /**
     * Extract metadata for a track URL (local file or remote URL)
     * Returns mimeType, duration, and size
     */
    private JSObject extractTrackMetadata(String url) throws Exception {
        JSObject metadata = new JSObject();

        // Determine MIME type from URL extension for remote files
        String mimeType = getMimeType(url);

        // Check if it's a local file
        if (url.startsWith("file://") || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            String filePath = url.startsWith("file://") ? url.substring(7) : url;
            File file = new File(filePath);

            if (file.exists()) {
                // Get file size
                metadata.put("size", file.length());

                // Use AudioFileProcessor to get detailed audio info
                JSObject fileInfo = AudioFileProcessor.getAudioFileInfo(filePath);
                String fileMimeType = fileInfo.has("mimeType") ? fileInfo.getString("mimeType") : mimeType;
                Double duration = fileInfo.has("duration") ? fileInfo.getDouble("duration") : 0.0;

                metadata.put("mimeType", fileMimeType);
                metadata.put("duration", duration);
            } else {
                // File doesn't exist - provide defaults
                metadata.put("size", 0L);
                metadata.put("mimeType", mimeType);
                metadata.put("duration", 0.0);
            }
        } else {
            // Remote URL - we can't get file size without downloading
            metadata.put("size", 0L);
            metadata.put("mimeType", mimeType);
            metadata.put("duration", 0.0);

            // Note: For remote URLs, duration will be available after the MediaPlayer is prepared
            // The calling code should use waitForTracksMetadata() to get accurate duration
        }

        return metadata;
    }

    @NonNull
    private static String getMimeType(String url) {
        String mimeType = "audio/mpeg"; // default
        if (url.endsWith(".aac")) {
            mimeType = "audio/aac";
        } else if (url.endsWith(".m4a")) {
            mimeType = "audio/m4a";
        } else if (url.endsWith(".mp3")) {
            mimeType = "audio/mpeg";
        } else if (url.endsWith(".wav")) {
            mimeType = "audio/wav";
        } else if (url.endsWith(".ogg")) {
            mimeType = "audio/ogg";
        } else if (url.endsWith(".flac")) {
            mimeType = "audio/flac";
        }
        return mimeType;
    }

    @PluginMethod
    public void playAudio(PluginCall call) {
        try {
            String url = call.getString("url");
            if (url != null) {
                // Find track ID by URL and play
                String trackId = findTrackIdByUrl(url);
                if (trackId != null) {
                    playbackManager.play(trackId);
                } else {
                    call.reject("Track not found: " + url);
                    return;
                }
            } else {
                // Play first available track
                String currentTrackId = playbackManager.getCurrentTrackId();
                if (currentTrackId != null) {
                    playbackManager.play(currentTrackId);
                } else {
                    call.reject("No tracks available to play");
                    return;
                }
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
                // Find track ID by URL and pause
                String trackId = findTrackIdByUrl(url);
                if (trackId != null) {
                    playbackManager.pause(trackId);
                } else {
                    call.reject("Track not found: " + url);
                    return;
                }
            } else {
                // Pause current track
                String currentTrackId = playbackManager.getCurrentTrackId();
                if (currentTrackId != null) {
                    playbackManager.pause(currentTrackId);
                }
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
                // Find track ID by URL and resume (play)
                String trackId = findTrackIdByUrl(url);
                if (trackId != null) {
                    playbackManager.play(trackId);
                } else {
                    call.reject("Track not found: " + url);
                    return;
                }
            } else {
                // Resume current track
                String currentTrackId = playbackManager.getCurrentTrackId();
                if (currentTrackId != null) {
                    playbackManager.play(currentTrackId);
                }
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
                // Find track ID by URL and stop
                String trackId = findTrackIdByUrl(url);
                if (trackId != null) {
                    playbackManager.stop(trackId);
                } else {
                    call.reject("Track not found: " + url);
                    return;
                }
            } else {
                // Stop current track
                String currentTrackId = playbackManager.getCurrentTrackId();
                if (currentTrackId != null) {
                    playbackManager.stop(currentTrackId);
                }
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
            // Find track ID by URL and seek
            String trackId = findTrackIdByUrl(url);
            if (trackId != null) {
                playbackManager.seekTo(trackId, seconds);
            } else {
                call.reject("Track not found: " + url);
                return;
            }
        } else {
            // Seek in current track
            String currentTrackId = playbackManager.getCurrentTrackId();
            if (currentTrackId != null) {
                playbackManager.seekTo(currentTrackId, seconds);
            } else {
                call.reject("No current track to seek");
                return;
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void skipToNext(PluginCall call) {
        // Simplified implementation - just return success since we don't have playlist functionality
        call.resolve();
    }

    @PluginMethod
    public void skipToPrevious(PluginCall call) {
        // Simplified implementation - just return success since we don't have playlist functionality
        call.resolve();
    }

    @PluginMethod
    public void skipToIndex(PluginCall call) {
        // Simplified implementation - just return success since we don't have playlist functionality
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

            String currentTrackId = playbackManager.getCurrentTrackId();
            if (currentTrackId != null) {
                // Find the URL for the current track
                String currentUrl = playbackManager.getCurrentTrackUrl();

                JSObject currentTrack = new JSObject();
                currentTrack.put("id", currentTrackId);
                currentTrack.put("url", currentUrl != null ? currentUrl : "");

                result.put("currentTrack", currentTrack);
                result.put("currentIndex", 0); // Single track playback
                result.put("currentPosition", playbackManager.getCurrentPosition(currentTrackId));
                result.put("duration", playbackManager.getDuration(currentTrackId));
                result.put("isPlaying", playbackManager.isPlaying(currentTrackId));
            } else {
                result.put("currentTrack", JSObject.NULL);
                result.put("currentIndex", -1);
                result.put("currentPosition", 0.0);
                result.put("duration", 0.0);
                result.put("isPlaying", false);
            }

            call.resolve(result);
        } catch (Exception e) {
            call.reject("Failed to get playback info: " + e.getMessage());
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

    // Helper method to find track ID by URL
    private String findTrackIdByUrl(String url) {
        return urlToTrackIdMap.get(url);
    }

    // PlaybackManagerListener implementation (Simplified)
    @Override
    public void onPlaybackStarted(String trackId) {
        try {
            String url = playbackManager.getCurrentTrackUrl();
            JSObject data = new JSObject();
            data.put("trackId", trackId);
            data.put("url", url != null ? url : "");
            notifyListeners("playbackStarted", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback started", e);
        }
    }

    @Override
    public void onPlaybackPaused(String trackId) {
        try {
            String url = playbackManager.getCurrentTrackUrl();
            JSObject data = new JSObject();
            data.put("trackId", trackId);
            data.put("url", url != null ? url : "");
            data.put("position", playbackManager.getCurrentPosition(trackId));
            notifyListeners("playbackPaused", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback paused", e);
        }
    }

    @Override
    public void onPlaybackStopped(String trackId) {
        try {
            String url = playbackManager.getCurrentTrackUrl();
            JSObject data = new JSObject();
            data.put("trackId", trackId);
            data.put("url", url != null ? url : "");
            notifyListeners("playbackStopped", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback stopped", e);
        }
    }

    @Override
    public void onPlaybackError(String trackId, String error) {
        JSObject data = new JSObject();
        data.put("trackId", trackId);
        data.put("message", error);
        notifyListeners("playbackError", data);
    }

    @Override
    public void onPlaybackProgress(String trackId, long currentPosition, long duration) {
        try {
            String url = playbackManager.getCurrentTrackUrl();
            JSObject data = new JSObject();
            data.put("trackId", trackId);
            data.put("url", url != null ? url : "");
            data.put("currentPosition", currentPosition / 1000.0); // Convert to seconds
            data.put("duration", duration / 1000.0); // Convert to seconds
            data.put("isPlaying", playbackManager.isPlaying(trackId));
            notifyListeners("playbackProgress", data);
        } catch (Exception e) {
            Log.e(TAG, "Error notifying playback progress", e);
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();

        Log.d(TAG, "Plugin destroying - cleaning up all resources");

        try {
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

            call.resolve();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start stream", e);
            call.reject("RECORDING_START_ERROR", e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void stopRecording(PluginCall call) {
        try {
            recordingManager.stopRecording();

            // Stop wave level monitoring
            if (waveLevelEmitter != null && waveLevelEmitter.isMonitoring()) {
                try {
                    waveLevelEmitter.stopMonitoring();
                    Log.d(TAG, "Wave level monitoring stopped with recording");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to stop wave level monitoring", e);
                }
            }

            // Get the recording status to retrieve the file path
            RecordingManager.StatusInfo status = recordingManager.getStatus();

            if (status.path() == null || status.path().isEmpty()) {
                call.reject("RECORDING_STOP_ERROR", "No recording file path available");
                return;
            }

            // Get audio file info
            JSObject audioInfo = AudioFileProcessor.getAudioFileInfo(status.path());
            call.resolve(audioInfo);
        } catch (Exception e) {
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

}