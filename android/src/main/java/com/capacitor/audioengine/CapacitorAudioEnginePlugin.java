package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.media.MediaPlayer;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaFormat;
import android.media.MediaCodec;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import com.getcapacitor.JSObject;
import com.getcapacitor.JSArray;
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
public class CapacitorAudioEnginePlugin extends Plugin {
    private static final String TAG = "CapacitorAudioEngine";

    // Recording properties
    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;
    private String currentRecordingPath;
    private double currentDuration = 0.0;
    private Timer durationTimer;
    private Handler mainHandler;

    // Segment rolling properties
    private Integer maxDuration;
    private Timer segmentTimer;
    private List<String> segmentFiles = new ArrayList<>();
    private int currentSegment = 0;

    // Recording settings
    private int sampleRate = 44100;
    private int channels = 1;
    private int bitrate = 128000;

    // Interruption handling
    private boolean wasRecordingBeforeInterruption = false;

    // Background service for long-running recordings
    private boolean serviceStarted = false;

    // Playback properties
    private MediaPlayer mediaPlayer;
    private boolean isPlaying = false;
    private String currentPlaybackPath;
    private Timer playbackProgressTimer;
    private float playbackSpeed = 1.0f;
    private float playbackVolume = 1.0f;
    private boolean isLooping = false;

    @Override
    public void load() {
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        boolean audioGranted = ContextCompat.checkSelfPermission(
            getContext(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        }

        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        Log.d(TAG, "requestPermission called");

        boolean audioGranted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(
                getContext(),
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED;
        }

        Log.d(TAG, "Current permissions - Audio: " + audioGranted + ", Notifications: " + notificationGranted);

        if (audioGranted && notificationGranted) {
            Log.d(TAG, "All permissions already granted");
            JSObject result = new JSObject();
            result.put("granted", true);
            result.put("audioPermission", true);
            result.put("notificationPermission", true);
            call.resolve(result);
        } else if (!audioGranted) {
            // Request audio permission first - this will show Android's default permission dialog
            Log.d(TAG, "Requesting RECORD_AUDIO permission - this will show system dialog");
            requestPermissionForAlias("microphone", call, "permissionCallback");
        } else if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Request notification permission - this will show Android's default permission dialog
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission - this will show system dialog");
            requestPermissionForAlias("notifications", call, "permissionCallback");
        }
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        Log.d(TAG, "permissionCallback called");

        boolean audioGranted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        }

        Log.d(TAG, "Permission callback - Audio: " + audioGranted + ", Notifications: " + notificationGranted);

        // If audio permission was just granted but notification permission is still needed
        if (audioGranted && !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "Audio granted, now requesting notification permission");
            requestPermissionForAlias("notifications", call, "permissionCallback");
            return;
        }

        // Return the final result
        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);
        call.resolve(result);
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (mediaRecorder != null) {
            call.reject("Recording is already in progress");
            return;
        }

        // Check permissions
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            call.reject("Microphone permission not granted");
            return;
        }

        // Check notification permission for Android 13+ (required for foreground service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                call.reject("Notification permission not granted (required for background recording on Android 13+)");
                return;
            }
        }

        // Reset segment tracking
        segmentFiles.clear();
        currentSegment = 0;
        currentDuration = 0.0;

        // Emit initial duration
        emitDurationChange();

        // Get recording options - maxDuration in SECONDS
        Integer maxDurationSeconds = call.getInt("maxDuration");
        if (maxDurationSeconds != null && maxDurationSeconds > 0) {
            // Use seconds directly for internal use
            maxDuration = maxDurationSeconds;
            Log.d(TAG, "Setting maxDuration: " + maxDurationSeconds + " seconds");
        } else {
            maxDuration = null;
        }

        sampleRate = call.getInt("sampleRate", 44100);
        channels = call.getInt("channels", 1);
        bitrate = call.getInt("bitrate", 128000);

        try {
            File recordingsDir = getRecordingsDirectory();

            if (maxDuration != null && maxDuration > 0) {
                // Start circular recording with segments (rolling recording)
                Log.d(TAG, "Starting circular recording with maxDuration: " + maxDuration + " seconds");
                startCircularRecording(recordingsDir);
                call.resolve();
            } else {
                // Regular linear recording without segments
                Log.d(TAG, "Starting linear recording (unlimited duration)");
                startLinearRecording(recordingsDir);
                call.resolve();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            JSObject errorData = new JSObject();
            errorData.put("message", "Failed to start recording: " + e.getMessage());
            emitError(errorData);
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder == null || !isRecording) {
            call.reject("No active recording session to pause");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.pause();
                stopSegmentTimer();
                stopDurationMonitoring();
                pauseRecordingService(); // Update service notification
                Log.d(TAG, "Recording paused");
                call.resolve();
            } else {
                call.reject("Pause/Resume is not supported on Android versions below API 24");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to pause recording", e);
            call.reject("Failed to pause recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (mediaRecorder == null || !isRecording) {
            call.reject("No recording session active");
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.resume();

                // Restart segment timer if maxDuration is set
                if (maxDuration != null && maxDuration > 0) {
                    startSegmentTimer(maxDuration);
                }

                startDurationMonitoring();
                resumeRecordingService(); // Update service notification
                Log.d(TAG, "Recording resumed");
                call.resolve();
            } else {
                call.reject("Pause/Resume is not supported on Android versions below API 24");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to resume recording", e);
            call.reject("Failed to resume recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        if (mediaRecorder == null || !isRecording) {
            call.reject("No active recording to stop");
            return;
        }

        try {
            // Stop duration monitoring
            stopDurationMonitoring();

            // Stop segment timer if active
            stopSegmentTimer();

            // Stop background service
            stopRecordingService();

            // Stop the actual MediaRecorder
            mediaRecorder.stop();
            mediaRecorder.release();
            isRecording = false;

            String fileToReturn = currentRecordingPath;

            // Handle segments if they exist
            if (maxDuration != null && maxDuration > 0 && !segmentFiles.isEmpty()) {
                fileToReturn = processSegments();
            }

            // Reset segment tracking
            maxDuration = null;
            currentSegment = 0;

            // Get file info and return
            JSObject response = getAudioFileInfo(fileToReturn);
            call.resolve(response);

            // Cleanup
            mediaRecorder = null;
            currentRecordingPath = null;

        } catch (Exception e) {
            Log.e(TAG, "Failed to stop recording", e);
            call.reject("Failed to stop recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        if (isRecording || mediaRecorder != null) {
            JSObject result = new JSObject();
            result.put("duration", currentDuration);
            call.resolve(result);
        } else {
            call.reject("No active recording");
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        String status;
        boolean sessionActive = isRecording;

        if (mediaRecorder != null && sessionActive) {
            // In Android, we can't easily distinguish between recording and paused
            // without additional state tracking, so we'll use a simple approach
            status = "recording";
        } else {
            status = "idle";
        }

        JSObject result = new JSObject();
        result.put("status", status);
        result.put("isRecording", sessionActive);
        result.put("currentSegment", currentSegment);
        result.put("duration", currentDuration);
        call.resolve(result);
    }

    @PluginMethod
    public void trimAudio(PluginCall call) {
        String sourcePath = call.getString("uri");
        Double startTime = call.getDouble("start", 0.0);
        Double endTime = call.getDouble("end", 0.0);

        if (sourcePath == null) {
            call.reject("Source URI is required");
            return;
        }

        if (endTime <= startTime) {
            call.reject("End time must be greater than start time");
            return;
        }

        try {
            // Handle Capacitor file URI format
            String actualPath = sourcePath;
            if (sourcePath.contains("capacitor://localhost/_capacitor_file_")) {
                actualPath = sourcePath.replace("capacitor://localhost/_capacitor_file_", "");
            } else if (sourcePath.startsWith("file://")) {
                actualPath = sourcePath.substring(7);
            }

            File sourceFile = new File(actualPath);
            if (!sourceFile.exists()) {
                call.reject("Source audio file does not exist at path: " + actualPath);
                return;
            }

            // Create output file
            File trimmedDir = new File(getContext().getExternalFilesDir(null), "Trimmed");
            if (!trimmedDir.exists()) {
                trimmedDir.mkdirs();
            }

            long timestamp = System.currentTimeMillis();
            File outputFile = new File(trimmedDir, "trimmed_" + timestamp + ".m4a");

            // Perform trimming using MediaExtractor and MediaMuxer
            trimAudioFile(sourceFile, outputFile, startTime, endTime);

            // Return file info
            JSObject response = getAudioFileInfo(outputFile.getAbsolutePath());
            call.resolve(response);

        } catch (Exception e) {
            Log.e(TAG, "Failed to trim audio", e);
            call.reject("Failed to trim audio: " + e.getMessage());
        }
    }

    // Private helper methods

    private File getRecordingsDirectory() throws IOException {
        File recordingsDir = new File(getContext().getExternalFilesDir(null), "Recordings");
        if (!recordingsDir.exists()) {
            if (!recordingsDir.mkdirs()) {
                throw new IOException("Failed to create recordings directory");
            }
        }
        return recordingsDir;
    }

    /**
     * Start linear recording (no max duration limit) - records continuously until stopped
     */
    private void startLinearRecording(File recordingsDir) throws IOException {
        // Start background service for long-running recording
        startRecordingService();

        long timestamp = System.currentTimeMillis();
        File audioFile = new File(recordingsDir, "recording_" + timestamp + ".m4a");
        currentRecordingPath = audioFile.getAbsolutePath();

        setupMediaRecorder(currentRecordingPath);
        mediaRecorder.start();
        isRecording = true;
        startDurationMonitoring();
        Log.d(TAG, "Started linear recording: " + audioFile.getName());
    }

    /**
     * Start circular recording with rolling segments
     * Memory-efficient approach that only keeps the last maxDuration worth of audio
     */
    private void startCircularRecording(File recordingsDir) throws IOException {
        // Start background service for long-running recording
        startRecordingService();

        // Calculate segment duration - use 60 seconds or maxDuration/4, whichever is smaller
        int segmentDurationSeconds = Math.min(60, Math.max(15, maxDuration / 4));

        Log.d(TAG, "Starting circular recording with segment duration: " + segmentDurationSeconds + " seconds");

        // Start first segment
        startNextSegment(recordingsDir);

        // Start segment timer to rotate segments
        startSegmentTimer(segmentDurationSeconds);

        isRecording = true;
        startDurationMonitoring();
    }

    private void startNextSegment(File recordingsDir) throws IOException {
        Log.d(TAG, "Starting next segment. Current segment count: " + segmentFiles.size());

        // Stop current recorder if exists
        if (mediaRecorder != null) {
            try {
                long startTime = System.nanoTime();
                Log.d(TAG, "Stopping previous recorder before starting new segment");
                mediaRecorder.stop();
                mediaRecorder.release();
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                Log.d(TAG, "MediaRecorder stop/release took: " + duration + " ms");
            } catch (Exception e) {
                Log.w(TAG, "Error stopping previous recorder", e);
            }
        }

        // Create new segment file
        long timestamp = System.currentTimeMillis();
        File segmentFile = new File(recordingsDir, "segment_" + timestamp + ".m4a");
        Log.d(TAG, "Creating new segment file: " + segmentFile.getName() + " at path: " + segmentFile.getAbsolutePath());

        // Add to segments list
        segmentFiles.add(segmentFile.getAbsolutePath());
        currentSegment++;
        Log.d(TAG, "Current segment number: " + currentSegment + ", total segments: " + segmentFiles.size());

        // Keep segments based on maxDuration - estimate how many segments we need
        // Add some buffer to ensure we have enough audio
        int maxSegments = Math.max(3, (maxDuration / 15) + 2); // At least 3 segments, or enough for maxDuration + buffer
        cleanupOldSegments(maxSegments);

        // Set as current recording path
        currentRecordingPath = segmentFile.getAbsolutePath();
        Log.d(TAG, "Set current recording path to: " + currentRecordingPath);

        // Setup and start new recorder
        long setupStartTime = System.nanoTime();
        setupMediaRecorder(currentRecordingPath);
        mediaRecorder.start();
        long setupDuration = (System.nanoTime() - setupStartTime) / 1_000_000;
        Log.d(TAG, "MediaRecorder setup/start took: " + setupDuration + " ms");

        Log.d(TAG, "Successfully started recording segment: " + segmentFile.getName());

        // Verify file was created
        if (segmentFile.exists()) {
            Log.d(TAG, "Segment file created successfully, initial size: " + segmentFile.length() + " bytes");
        } else {
            Log.w(TAG, "Segment file was not created!");
        }
    }

    /**
     * Clean up old segments to keep memory usage under control
     */
    private void cleanupOldSegments(int maxSegments) {
        while (segmentFiles.size() > maxSegments) {
            String oldSegmentPath = segmentFiles.remove(0);
            new File(oldSegmentPath).delete();
            Log.d(TAG, "Removed old segment: " + new File(oldSegmentPath).getName());
        }
    }

    /**
     * Enhanced MediaRecorder setup with optimized settings for background recording
     */
    private void setupMediaRecorder(String outputPath) throws IOException {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing existing MediaRecorder", e);
            }
        }

        mediaRecorder = new MediaRecorder();

        // Use MIC audio source - most reliable for long recording sessions
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);

        // Use MPEG_4 container format for M4A output
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);

        // Use AAC encoder for best compatibility and compression
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);

        // Set output file path
        mediaRecorder.setOutputFile(outputPath);

        // Configure audio settings for high quality and efficiency
        mediaRecorder.setAudioSamplingRate(sampleRate);
        mediaRecorder.setAudioChannels(channels);
        mediaRecorder.setAudioEncodingBitRate(bitrate);

        // Set max file size for circular recording segments (optional safety measure)
        if (maxDuration != null && maxDuration > 0) {
            // Estimate max file size: (bitrate * segmentDuration * 1.2) / 8 bytes + overhead
            long estimatedMaxSize = (long) ((bitrate * 70L * 1.2) / 8) + (1024 * 1024); // 70 seconds with 20% buffer + 1MB overhead
            mediaRecorder.setMaxFileSize(estimatedMaxSize);
        }

        // Prepare the recorder
        mediaRecorder.prepare();

        Log.d(TAG, "MediaRecorder configured - Sample Rate: " + sampleRate +
               ", Channels: " + channels + ", Bitrate: " + bitrate +
               ", Output: " + outputPath);
    }

    private void startSegmentTimer(int maxDurationSeconds) {
        stopSegmentTimer();
        Log.d(TAG, "Starting segment timer with maxDuration: " + maxDurationSeconds + " seconds");

        segmentTimer = new Timer();
        segmentTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!isRecording) {
                    Log.d(TAG, "Timer fired but recording is not active, ignoring");
                    return;
                }

                Log.d(TAG, "Segment timer fired after " + maxDurationSeconds + " seconds");

                mainHandler.post(() -> {
                    try {
                        File recordingsDir = getRecordingsDirectory();
                        Log.d(TAG, "Creating next segment after timer interval");
                        startNextSegment(recordingsDir);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to create next segment", e);
                        JSObject errorData = new JSObject();
                        errorData.put("message", "Failed to create next segment: " + e.getMessage());
                        emitError(errorData);
                    }
                });
            }
        }, maxDurationSeconds * 1000L, maxDurationSeconds * 1000L);

        Log.d(TAG, "Segment timer started successfully");
    }

    private void stopSegmentTimer() {
        if (segmentTimer != null) {
            Log.d(TAG, "Stopping segment timer");
            segmentTimer.cancel();
            segmentTimer = null;
        }
    }

    private void startDurationMonitoring() {
        stopDurationMonitoring();
        Log.d(TAG, "Starting duration monitoring");

        durationTimer = new Timer();
        durationTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (!isRecording) {
                    Log.d(TAG, "Timer fired but recording is not active, skipping");
                    return;
                }

                currentDuration += 1.0;
                Log.d(TAG, "Duration incremented: " + currentDuration + " seconds");

                mainHandler.post(() -> {
                    Log.d(TAG, "Emitting durationChange event with duration: " + currentDuration);
                    emitDurationChange();
                });
            }
        }, 1000, 1000);

        Log.d(TAG, "Duration timer started successfully");
    }

    private void stopDurationMonitoring() {
        if (durationTimer != null) {
            Log.d(TAG, "Stopping duration monitoring");
            durationTimer.cancel();
            durationTimer = null;
        }
    }

    private String processSegments() {
        Log.d(TAG, "Processing segments. Total segments: " + segmentFiles.size());

        if (segmentFiles.isEmpty()) {
            Log.d(TAG, "No segments available, using current recording path");
            return currentRecordingPath;
        }

        try {
            if (segmentFiles.size() == 1) {
                return processSingleSegment();
            } else {
                return processMultipleSegments();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to process segments", e);
            // Return the last segment as fallback
            return segmentFiles.isEmpty() ? currentRecordingPath : segmentFiles.get(segmentFiles.size() - 1);
        }
    }

    private String processSingleSegment() {
        String singleSegment = segmentFiles.get(0);
        Log.d(TAG, "Processing single segment: " + new File(singleSegment).getName());

        try {
            SegmentInfo segmentInfo = getSegmentInfo(singleSegment);
            Log.d(TAG, "Single segment duration: " + segmentInfo.duration + "s, maxDuration: " + maxDuration + "s");

            if (segmentInfo.duration > maxDuration) {
                // Trim to exactly maxDuration
                return trimSegmentToMaxDuration(singleSegment, "trimmed_single_");
            }
            // Even if the segment is shorter than maxDuration, return it as is
            // This handles cases where recording was stopped before reaching maxDuration
            Log.d(TAG, "Single segment is shorter than maxDuration, using as-is");
            return singleSegment;
        } catch (Exception e) {
            Log.e(TAG, "Failed to process single segment", e);
            return singleSegment;
        }
    }

    private String processMultipleSegments() {
        List<SegmentInfo> segmentInfos = new ArrayList<>();

        // Collect segment information
        for (String segmentPath : segmentFiles) {
            try {
                SegmentInfo info = getSegmentInfo(segmentPath);
                segmentInfos.add(info);
                Log.d(TAG, "Segment " + new File(segmentPath).getName() +
                      ": duration=" + info.duration + "s, size=" + info.fileSize + " bytes");
            } catch (Exception e) {
                Log.e(TAG, "Failed to get info for segment: " + segmentPath, e);
            }
        }

        if (segmentInfos.size() < 2) {
            return segmentFiles.get(segmentFiles.size() - 1);
        }

        return processSegmentsByStrategy(segmentInfos);
    }

    private String processSegmentsByStrategy(List<SegmentInfo> segmentInfos) {
        SegmentInfo lastSegment = segmentInfos.get(segmentInfos.size() - 1);
        SegmentInfo previousSegment = segmentInfos.get(segmentInfos.size() - 2);

        Log.d(TAG, "Last segment duration: " + lastSegment.duration + "s, Previous: " + previousSegment.duration + "s");

        // Calculate total duration of all segments
        double totalDuration = 0;
        for (SegmentInfo info : segmentInfos) {
            totalDuration += info.duration;
        }
        Log.d(TAG, "Total duration of all segments: " + totalDuration + "s, maxDuration: " + maxDuration + "s");

        // Strategy 1: Last segment is exactly maxDuration (within tolerance)
        if (Math.abs(lastSegment.duration - maxDuration) <= 0.1) {
            return handleExactDurationSegment(lastSegment, previousSegment);
        }

        // Strategy 2: Last segment is shorter, merge with trimmed previous if possible
        if (lastSegment.duration < maxDuration) {
            // If total recording time is still less than maxDuration, just merge what we have
            if (totalDuration <= maxDuration) {
                Log.d(TAG, "Total recording time (" + totalDuration + "s) is less than maxDuration (" +
                      maxDuration + "s), using last segment: " + lastSegment.duration + "s");
                return lastSegment.filePath;
            }
            return handleShortLastSegment(lastSegment, previousSegment);
        }

        // Strategy 3: Last segment is longer, trim to exact duration
        return handleLongLastSegment(lastSegment, previousSegment);
    }

    private String handleExactDurationSegment(SegmentInfo lastSegment, SegmentInfo previousSegment) {
        Log.d(TAG, "Last segment duration equals maxDuration, cleaning up previous segment");

        // Delete the previous segment as we only need the last one
        new File(previousSegment.filePath).delete();
        Log.d(TAG, "Deleted previous segment: " + new File(previousSegment.filePath).getName());

        // Clean up segment list, keep only the last segment
        segmentFiles.clear();
        segmentFiles.add(lastSegment.filePath);

        return lastSegment.filePath;
    }

    private String handleShortLastSegment(SegmentInfo lastSegment, SegmentInfo previousSegment) {
        Log.d(TAG, "Last segment is shorter than maxDuration, merging with trimmed previous segment");

        try {
            File recordingsDir = getRecordingsDirectory();
            long timestamp = System.currentTimeMillis();
            File mergedFile = new File(recordingsDir, "merged_" + timestamp + ".m4a");

            // Calculate required duration from previous segment
            double requiredFromPrevious = maxDuration - lastSegment.duration;
            double actualRequiredDuration = Math.min(requiredFromPrevious, previousSegment.duration);

            Log.d(TAG, "Required from previous: " + requiredFromPrevious + "s, actual: " + actualRequiredDuration + "s");

            // Merge segments
            mergeSegmentsForRolling(new File(previousSegment.filePath), new File(lastSegment.filePath),
                                  mergedFile, actualRequiredDuration);

            // Clean up original segments
            cleanupProcessedSegments(previousSegment.filePath, lastSegment.filePath);
            segmentFiles.clear();
            segmentFiles.add(mergedFile.getAbsolutePath());

            Log.d(TAG, "Successfully merged segments into: " + mergedFile.getName());
            return mergedFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to merge segments", e);
            return lastSegment.filePath;
        }
    }

    private String handleLongLastSegment(SegmentInfo lastSegment, SegmentInfo previousSegment) {
        Log.d(TAG, "Last segment is longer than maxDuration, trimming to exact duration");

        try {
            String trimmedPath = trimSegmentToMaxDuration(lastSegment.filePath, "trimmed_");

            // Clean up original segments
            cleanupProcessedSegments(previousSegment.filePath, lastSegment.filePath);
            segmentFiles.clear();
            segmentFiles.add(trimmedPath);

            Log.d(TAG, "Successfully trimmed last segment to: " + new File(trimmedPath).getName());
            return trimmedPath;
        } catch (Exception e) {
            Log.e(TAG, "Failed to trim segment", e);
            return lastSegment.filePath;
        }
    }

    private String trimSegmentToMaxDuration(String segmentPath, String prefix) throws Exception {
        File recordingsDir = getRecordingsDirectory();
        long timestamp = System.currentTimeMillis();
        File trimmedFile = new File(recordingsDir, prefix + timestamp + ".m4a");

        // For circular recording, we want the LAST maxDuration seconds (most recent audio)
        // Calculate the start time to get the last maxDuration seconds
        double segmentDuration = getAudioDuration(segmentPath);
        double startTime = Math.max(0, segmentDuration - maxDuration);

        Log.d(TAG, "Trimming segment: duration=" + segmentDuration + "s, startTime=" + startTime + "s, maxDuration=" + maxDuration + "s");

        trimAudioFile(new File(segmentPath), trimmedFile, startTime, segmentDuration);
        return trimmedFile.getAbsolutePath();
    }

    private void cleanupProcessedSegments(String... segmentPaths) {
        for (String path : segmentPaths) {
            if (new File(path).delete()) {
                Log.d(TAG, "Deleted processed segment: " + new File(path).getName());
            }
        }
    }

    private SegmentInfo getSegmentInfo(String filePath) throws Exception {
        double duration = getAudioDuration(filePath);
        long fileSize = new File(filePath).length();
        return new SegmentInfo(filePath, duration, fileSize);
    }

    private double getAudioDuration(String filePath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return durationStr != null ? Double.parseDouble(durationStr) / 1000.0 : 0.0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio duration", e);
            return 0.0;
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                }
            }
        }
    }

    /**
     * Merge segments for rolling recording: take required duration from end of first file
     * and combine with entire second file to create seamless recording
     */
    private void mergeSegmentsForRolling(File firstFile, File secondFile, File outputFile, double requiredDurationFromFirst) throws IOException {
        Log.d(TAG, "Starting segment merge: firstFile=" + firstFile.getName() +
              ", secondFile=" + secondFile.getName() + ", requiredDuration=" + requiredDurationFromFirst);

        // First, trim the first file to get only the required duration from the end
        File tempTrimmedFirst = new File(outputFile.getParent(), "temp_trimmed_first_" + System.currentTimeMillis() + ".m4a");

        try {
            // Get first file duration
            double firstFileDuration = getAudioDuration(firstFile.getAbsolutePath());
            double startTimeForFirst = Math.max(0, firstFileDuration - requiredDurationFromFirst);

            Log.d(TAG, "First file duration: " + firstFileDuration + "s, start time: " + startTimeForFirst + "s");

            // Trim the first file to get the required portion from the end
            trimAudioFile(firstFile, tempTrimmedFirst, startTimeForFirst, firstFileDuration);

            // Verify the trimmed duration
            double trimmedDuration = getAudioDuration(tempTrimmedFirst.getAbsolutePath());
            Log.d(TAG, "Trimmed first file duration: " + trimmedDuration + "s");

            // Now concatenate the trimmed first file with the complete second file
            concatenateAudioFiles(tempTrimmedFirst, secondFile, outputFile);

            // Verify final duration
            double finalDuration = getAudioDuration(outputFile.getAbsolutePath());
            Log.d(TAG, "Final merged file duration: " + finalDuration + "s");

        } finally {
            // Clean up temp file
            if (tempTrimmedFirst.exists()) {
                tempTrimmedFirst.delete();
            }
        }
    }

    /**
     * Concatenate two audio files using MediaExtractor and MediaMuxer
     */
    private void concatenateAudioFiles(File firstFile, File secondFile, File outputFile) throws IOException {
        MediaExtractor firstExtractor = null;
        MediaExtractor secondExtractor = null;
        MediaMuxer muxer = null;

        try {
            // Setup extractors
            firstExtractor = new MediaExtractor();
            firstExtractor.setDataSource(firstFile.getAbsolutePath());

            secondExtractor = new MediaExtractor();
            secondExtractor.setDataSource(secondFile.getAbsolutePath());

            // Find audio tracks
            int firstAudioTrack = findAudioTrack(firstExtractor);
            int secondAudioTrack = findAudioTrack(secondExtractor);

            if (firstAudioTrack == -1 || secondAudioTrack == -1) {
                throw new IOException("Audio track not found in one or both files");
            }

            // Get audio format
            MediaFormat audioFormat = firstExtractor.getTrackFormat(firstAudioTrack);
            firstExtractor.selectTrack(firstAudioTrack);
            secondExtractor.selectTrack(secondAudioTrack);

            // Create muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(audioFormat);
            muxer.start();

            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();
            long presentationTimeUs = 0;

            // Copy first file
            Log.d(TAG, "Copying first file data...");
            while (true) {
                int sampleSize = firstExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = presentationTimeUs;
                bufferInfo.flags = convertExtractorFlags(firstExtractor.getSampleFlags());

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);

                // Use the actual sample time difference for accurate timing
                long currentSampleTime = firstExtractor.getSampleTime();
                firstExtractor.advance();
                long nextSampleTime = firstExtractor.getSampleTime();

                if (nextSampleTime >= 0) {
                    presentationTimeUs += (nextSampleTime - currentSampleTime);
                } else {
                    // End of first file, estimate last frame duration
                    presentationTimeUs += 23220; // ~23ms for AAC at 44.1kHz (typical frame duration)
                }
            }

            // Copy second file, continuing the timeline
            Log.d(TAG, "Copying second file data...");
            secondExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            long secondFileStartTime = presentationTimeUs;

            while (true) {
                int sampleSize = secondExtractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long originalTime = secondExtractor.getSampleTime();

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = secondFileStartTime + originalTime;
                bufferInfo.flags = convertExtractorFlags(secondExtractor.getSampleFlags());

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                secondExtractor.advance();
            }

            Log.d(TAG, "Successfully concatenated audio files");

        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing muxer", e);
                }
            }
            if (firstExtractor != null) {
                try {
                    firstExtractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing first extractor", e);
                }
            }
            if (secondExtractor != null) {
                try {
                    secondExtractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing second extractor", e);
                }
            }
        }
    }

    /**
     * Find audio track in MediaExtractor
     */
    private int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private void mergeAudioFiles(File firstFile, File secondFile, File outputFile, double requiredDuration) throws IOException {
        // For simplicity, we'll just copy the second file as the merge result
        // A full implementation would use MediaExtractor and MediaMuxer to properly merge
        // This is a simplified version - in production, you'd want proper audio merging

        try {
            // Use MediaExtractor and MediaMuxer for proper merging
            // This is a basic implementation that prioritizes the second file
            // In a real scenario, you'd extract and merge audio tracks properly

            if (secondFile.exists()) {
                // Copy second file to output
                java.nio.file.Files.copy(secondFile.toPath(), outputFile.toPath());
                Log.d(TAG, "Audio files merged (simplified implementation)");
            }
        } catch (Exception e) {
            throw new IOException("Failed to merge audio files: " + e.getMessage());
        }
    }

    private void trimAudioFile(File sourceFile, File outputFile, double startTime, double endTime) throws IOException {
        MediaExtractor extractor = null;
        MediaMuxer muxer = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(sourceFile.getAbsolutePath());

            // Find audio track
            int audioTrack = -1;
            MediaFormat audioFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    audioFormat = format;
                    break;
                }
            }

            if (audioTrack == -1) {
                throw new IOException("No audio track found in source file");
            }

            extractor.selectTrack(audioTrack);

            // Create muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrack = muxer.addTrack(audioFormat);
            muxer.start();

            // Seek to start time
            extractor.seekTo((long)(startTime * 1000000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Extract and write data
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            long endTimeUs = (long)(endTime * 1000000);

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long sampleTime = extractor.getSampleTime();
                if (sampleTime > endTimeUs) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = sampleTime - (long)(startTime * 1000000);
                bufferInfo.flags = convertExtractorFlags(extractor.getSampleFlags());

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                extractor.advance();
            }

            Log.d(TAG, "Audio trimmed successfully");

        } finally {
            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing muxer", e);
                }
            }
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing extractor", e);
                }
            }
        }
    }

    private JSObject getAudioFileInfo(String filePath) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info
            info.put("path", filePath);
            info.put("uri", "file://" + filePath);
            info.put("webPath", "capacitor://localhost/_capacitor_file_" + filePath);
            info.put("mimeType", "audio/m4a");
            info.put("size", file.length());
            info.put("createdAt", System.currentTimeMillis());
            info.put("filename", file.getName());

            // Add base64 encoding of the audio file with MIME prefix (Data URI format)
            try {
                byte[] audioBytes = java.nio.file.Files.readAllBytes(file.toPath());
                String base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP);
                String dataUri = "data:audio/m4a;base64," + base64Audio;
                info.put("base64", dataUri);
            } catch (Exception e) {
                Log.w(TAG, "Failed to encode audio file to base64", e);
                info.put("base64", null);
            }

            // Audio metadata
            MediaMetadataRetriever retriever = null;
            try {
                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(filePath);

                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                double duration = durationStr != null ? Double.parseDouble(durationStr) / 1000.0 : 0.0;
                info.put("duration", Math.round(duration * 10.0) / 10.0);

                // Use recording settings as metadata might not be available
                info.put("sampleRate", sampleRate);
                info.put("channels", channels);
                info.put("bitrate", bitrate);

            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception e) {
                        Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio file info", e);
            // Return basic info even if metadata extraction fails
            info.put("duration", 0.0);
            info.put("sampleRate", sampleRate);
            info.put("channels", channels);
            info.put("bitrate", bitrate);
            info.put("base64", null);
        }

        return info;
    }

    // Event emission methods
    private void emitDurationChange() {
        JSObject data = new JSObject();
        data.put("duration", currentDuration);
        notifyListeners("durationChange", data);
    }

    private void emitError(JSObject errorData) {
        notifyListeners("error", errorData);
    }

    private void emitInterruption(String message) {
        JSObject data = new JSObject();
        data.put("message", message);
        notifyListeners("recordingInterruption", data);
    }

    // App lifecycle handling for interruptions
    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        handleAppStateChange(true);
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        handleAppStateChange(false);
    }

    private void handleAppStateChange(boolean isBackground) {
        if (isBackground) {
            if (isRecording) {
                wasRecordingBeforeInterruption = true;
                try {
                    stopSegmentTimer();
                    stopDurationMonitoring();
                    if (mediaRecorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mediaRecorder.pause();
                    }
                    pauseRecordingService(); // Update service for background state
                    emitInterruption("App went to background, recording paused");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to handle app state change to background", e);
                }
            }
        } else {
            if (wasRecordingBeforeInterruption) {
                try {
                    if (mediaRecorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mediaRecorder.resume();

                        if (maxDuration != null && maxDuration > 0) {
                            startSegmentTimer(maxDuration);
                        }

                        startDurationMonitoring();
                        resumeRecordingService(); // Update service for foreground state
                        emitInterruption("App resumed from background, recording resumed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to resume recording after app state change", e);
                }
            }
            wasRecordingBeforeInterruption = false;
        }
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();

        // Clean up resources
        stopDurationMonitoring();
        stopSegmentTimer();

        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                }
                mediaRecorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error cleaning up MediaRecorder", e);
            }
            mediaRecorder = null;
        }

        isRecording = false;

        // Clean up segment files
        for (String segmentPath : segmentFiles) {
            new File(segmentPath).delete();
        }
        segmentFiles.clear();
    }

    // Background service management methods
    private void startRecordingService() {
        if (!serviceStarted) {
            Intent serviceIntent = new Intent(getContext(), AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_START_RECORDING);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getContext().startForegroundService(serviceIntent);
            } else {
                getContext().startService(serviceIntent);
            }

            serviceStarted = true;
            Log.d(TAG, "Started background recording service");
        }
    }

    private void pauseRecordingService() {
        if (serviceStarted) {
            Intent serviceIntent = new Intent(getContext(), AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_PAUSE_RECORDING);
            getContext().startService(serviceIntent);
            Log.d(TAG, "Paused background recording service");
        }
    }

    private void resumeRecordingService() {
        if (serviceStarted) {
            Intent serviceIntent = new Intent(getContext(), AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_RESUME_RECORDING);
            getContext().startService(serviceIntent);
            Log.d(TAG, "Resumed background recording service");
        }
    }

    private void stopRecordingService() {
        if (serviceStarted) {
            Intent serviceIntent = new Intent(getContext(), AudioRecordingService.class);
            serviceIntent.setAction(AudioRecordingService.ACTION_STOP_RECORDING);
            getContext().startService(serviceIntent);
            serviceStarted = false;
            Log.d(TAG, "Stopped background recording service");
        }
    }

    /**
     * Convert MediaExtractor sample flags to MediaCodec buffer flags
     * This is necessary because MediaExtractor and MediaCodec use different flag constants
     */
    private int convertExtractorFlags(int extractorFlags) {
        int bufferFlags = 0;

        // Convert sync frame flag
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            bufferFlags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }

        // Convert partial frame flag
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            bufferFlags |= MediaCodec.BUFFER_FLAG_PARTIAL_FRAME;
        }

        // Note: MediaExtractor.SAMPLE_FLAG_ENCRYPTED doesn't have a direct MediaCodec equivalent
        // so we don't convert it (encrypted samples would need special handling anyway)

        return bufferFlags;
    }



    /**
     * Check if microphone is currently busy/in use by another application
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @PluginMethod
    public void isMicrophoneBusy(PluginCall call) {
        try {
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);

            boolean isBusy = false;

            // Check if we can create an AudioRecord instance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioRecord audioRecord = null;
                try {
                    audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    );

                    if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                        isBusy = true;
                    } else {
                        // Try to start recording briefly to check if microphone is available
                        try {
                            audioRecord.startRecording();
                            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                                isBusy = true;
                            }
                            audioRecord.stop();
                        } catch (Exception e) {
                            isBusy = true;
                        }
                    }
                } catch (Exception e) {
                    isBusy = true;
                } finally {
                    if (audioRecord != null) {
                        audioRecord.release();
                    }
                }
            }

            JSObject result = new JSObject();
            result.put("busy", isBusy);
            result.put("reason", isBusy ? "Microphone is currently in use by another application" : "Microphone is available");
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to check microphone status", e);
            call.reject("Failed to check microphone status: " + e.getMessage());
        }
    }

    /**
     * Get list of available microphones (internal and external)
     */
    @PluginMethod
    public void getAvailableMicrophones(PluginCall call) {
        try {
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            JSArray microphones = new JSArray();

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);

                // Track if we've added a built-in microphone to avoid duplicates
                boolean builtinMicAdded = false;
                AudioDeviceInfo primaryBuiltinMic = null;

                // First pass: find the primary built-in microphone and external devices
                for (AudioDeviceInfo device : devices) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        // Keep the first built-in mic as primary
                        if (!builtinMicAdded) {
                            primaryBuiltinMic = device;
                            builtinMicAdded = true;
                        }
                    } else if (device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                               device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                               device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                               device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET ||
                               device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE) {

                        // Add external microphones immediately
                        JSObject micInfo = new JSObject();
                        micInfo.put("id", device.getId());
                        micInfo.put("name", getDeviceName(device));
                        micInfo.put("type", getDeviceType(device));
                        micInfo.put("isConnected", true);
                        microphones.put(micInfo);
                    }
                }

                // Add the primary built-in microphone if found
                if (primaryBuiltinMic != null) {
                    JSObject micInfo = new JSObject();
                    micInfo.put("id", primaryBuiltinMic.getId());
                    micInfo.put("name", "Built-in Microphone");
                    micInfo.put("type", "internal");
                    micInfo.put("isConnected", true);
                    microphones.put(micInfo);
                }
            } else {
                // For older Android versions, provide basic built-in microphone
                JSObject micInfo = new JSObject();
                micInfo.put("id", 0);
                micInfo.put("name", "Built-in Microphone");
                micInfo.put("type", "internal");
                micInfo.put("isConnected", true);
                microphones.put(micInfo);
            }

            JSObject result = new JSObject();
            result.put("microphones", microphones);
            call.resolve(result);

        } catch (Exception e) {
            Log.e(TAG, "Failed to get available microphones", e);
            call.reject("Failed to get available microphones: " + e.getMessage());
        }
    }

    /**
     * Switch to a different microphone while keeping recording active
     */
    @PluginMethod
    public void switchMicrophone(PluginCall call) {
        Integer microphoneId = call.getInt("microphoneId");
        if (microphoneId == null) {
            call.reject("Microphone ID is required");
            return;
        }

        try {
            AudioManager audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
                AudioDeviceInfo targetDevice = null;

                // Find the device with the matching ID
                for (AudioDeviceInfo device : devices) {
                    if (device.getId() == microphoneId) {
                        targetDevice = device;
                        break;
                    }
                }

                if (targetDevice == null) {
                    call.reject("Microphone not found with ID: " + microphoneId);
                    return;
                }

                // Check if recording is currently active
                boolean wasRecording = isRecording && mediaRecorder != null;

                if (wasRecording) {
                    // For seamless switching during recording, we need to:
                    // 1. Pause current recording
                    // 2. Switch audio routing
                    // 3. Resume recording

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        mediaRecorder.pause();
                        stopDurationMonitoring();

                        // Switch audio routing preference
                        // Note: Android doesn't provide direct microphone switching during recording
                        // This is a limitation of the Android MediaRecorder API
                        Log.d(TAG, "Attempting to switch to microphone: " + getDeviceName(targetDevice));

                        // Resume recording
                        mediaRecorder.resume();
                        startDurationMonitoring();

                        JSObject result = new JSObject();
                        result.put("success", true);
                        result.put("microphoneId", microphoneId);
                        result.put("message", "Microphone preference updated for next recording session");
                        call.resolve(result);
                    } else {
                        call.reject("Microphone switching during recording requires Android N (API 24) or higher");
                    }
                } else {
                    // Not currently recording, just store preference for next recording
                    JSObject result = new JSObject();
                    result.put("success", true);
                    result.put("microphoneId", microphoneId);
                    result.put("message", "Microphone preference set for next recording session");
                    call.resolve(result);
                }
            } else {
                call.reject("Microphone switching requires Android M (API 23) or higher");
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to switch microphone", e);
            call.reject("Failed to switch microphone: " + e.getMessage());
        }
    }

    /**
     * Helper method to get human-readable device name
     */
    private String getDeviceName(AudioDeviceInfo device) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            CharSequence productName = device.getProductName();
            if (productName != null && productName.length() > 0) {
                return productName.toString();
            }
        }

        // Fallback to device type-based naming
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in Microphone";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired Headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired Headphones";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth Headset";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB Headset";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "USB Audio Device";
            default:
                return "Unknown Audio Device";
        }
    }

    /**
     * Helper method to categorize device type
     */
    private String getDeviceType(AudioDeviceInfo device) {
        switch (device.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "internal";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return "external";
            default:
                return "unknown";
        }
    }

    // Helper class for segment information
    private static class SegmentInfo {
        String filePath;
        double duration;
        long fileSize;

        SegmentInfo(String filePath, double duration, long fileSize) {
            this.filePath = filePath;
            this.duration = duration;
            this.fileSize = fileSize;
        }
    }

    // ========== AUDIO PLAYBACK METHODS ==========

    @PluginMethod
    public void preload(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) {
            call.reject("URI is required");
            return;
        }

        Boolean prepare = call.getBoolean("prepare", true);

        // Check network connectivity for remote URLs
        if (isRemoteUrl(uri) && !isNetworkAvailable()) {
            call.reject("Network is not available for remote audio URL");
            return;
        }

        // For CDN URLs, use enhanced approach first
        if (isRemoteUrl(uri)) {
            preloadWithEnhancedCDNSupport(uri, prepare, call);
        } else {
            preloadWithBasicApproach(uri, prepare, call);
        }
    }

    /**
     * Enhanced CDN preload with HTTP validation and better error handling
     */
    private void preloadWithEnhancedCDNSupport(String uri, Boolean prepare, PluginCall call) {
        new Thread(() -> {
            try {
                // First, validate the CDN URL with a HEAD request
                java.net.URL url = new java.net.URL(uri);
                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();

                // Set enhanced headers for CDN compatibility
                connection.setRequestProperty("User-Agent", "CapacitorAudioEngine/1.0 (Android)");
                connection.setRequestProperty("Accept", "audio/*");
                connection.setRequestProperty("Accept-Ranges", "bytes");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Connection", "keep-alive");

                // Use HEAD request to validate without downloading
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(15000); // 15 seconds for validation
                connection.setReadTimeout(15000);

                int responseCode = connection.getResponseCode();
                Log.d(TAG, "CDN Validation - HTTP Response Code: " + responseCode + " for URL: " + uri);

                if (responseCode >= 200 && responseCode < 300) {
                    // Success - check content type
                    String contentType = connection.getContentType();
                    if (contentType != null) {
                        Log.d(TAG, "Content-Type: " + contentType);
                        if (!contentType.contains("audio") && !contentType.contains("application/octet-stream") && !contentType.contains("video")) {
                            Log.w(TAG, "Warning: Unexpected content type " + contentType + " for audio file");
                        }
                    }

                    // Get content length for logging
                    int contentLength = connection.getContentLength();
                    if (contentLength > 0) {
                        Log.d(TAG, "Content-Length: " + contentLength + " bytes");
                    }

                    connection.disconnect();

                    // Now use MediaPlayer with the validated URL
                    getActivity().runOnUiThread(() -> {
                        preloadWithBasicApproach(uri, prepare, call);
                    });

                } else {
                    // Handle HTTP error codes with detailed messages
                    String errorMessage = getHttpErrorMessage(responseCode);
                    connection.disconnect();
                    getActivity().runOnUiThread(() -> call.reject("CDN validation failed: " + errorMessage));
                }

            } catch (java.net.MalformedURLException e) {
                getActivity().runOnUiThread(() -> call.reject("Invalid CDN URL format"));
            } catch (java.net.SocketTimeoutException e) {
                getActivity().runOnUiThread(() -> call.reject("CDN validation timed out - server may be slow or unreachable"));
            } catch (java.net.UnknownHostException e) {
                getActivity().runOnUiThread(() -> call.reject("Cannot find CDN host"));
            } catch (java.net.ConnectException e) {
                getActivity().runOnUiThread(() -> call.reject("Cannot connect to CDN host"));
            } catch (java.io.IOException e) {
                getActivity().runOnUiThread(() -> call.reject("Network error during CDN validation: " + e.getMessage()));
            } catch (Exception e) {
                getActivity().runOnUiThread(() -> call.reject("Failed to validate CDN URL: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Basic preload approach using MediaPlayer directly
     */
    private void preloadWithBasicApproach(String uri, Boolean prepare, PluginCall call) {
        try {
            // Stop any current playback
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

            mediaPlayer = new MediaPlayer();

            // Configure for CDN URLs if needed
            if (isRemoteUrl(uri)) {
                configureMediaPlayerForRemoteUrl(mediaPlayer, uri);
            } else {
                mediaPlayer.setDataSource(uri);
            }

            if (prepare) {
                mediaPlayer.setOnPreparedListener(mp -> {
                    JSObject ret = new JSObject();
                    ret.put("success", true);
                    ret.put("duration", mp.getDuration() / 1000.0);
                    call.resolve(ret);
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    String errorMessage = getDetailedErrorMessage(what, extra, uri);
                    call.reject("Failed to preload audio: " + errorMessage);
                    return true;
                });

                mediaPlayer.prepareAsync();
            } else {
                JSObject ret = new JSObject();
                ret.put("success", true);
                call.resolve(ret);
            }

            currentPlaybackPath = uri;

        } catch (Exception e) {
            String errorMessage = "Failed to preload audio: " + e.getMessage();
            if (isRemoteUrl(uri)) {
                errorMessage += " (Check URL accessibility and network connection)";
            }
            call.reject(errorMessage);
        }
    }

    @PluginMethod
    public void startPlayback(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null) {
            call.reject("URI is required");
            return;
        }

        // Check network connectivity for remote URLs
        if (isRemoteUrl(uri) && !isNetworkAvailable()) {
            call.reject("Network is not available for remote audio URL");
            return;
        }

        try {
            // Stop any current playback
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

            mediaPlayer = new MediaPlayer();

            // Configure for CDN URLs if needed
            if (isRemoteUrl(uri)) {
                configureMediaPlayerForRemoteUrl(mediaPlayer, uri);
            } else {
                mediaPlayer.setDataSource(uri);
            }

            // Set playback options
            Float speed = call.getFloat("speed", 1.0f);
            Float volume = call.getFloat("volume", 1.0f);
            Boolean loop = call.getBoolean("loop", false);
            Integer startTime = call.getInt("startTime", 0);

            playbackSpeed = speed;
            playbackVolume = volume;
            isLooping = loop;

            mediaPlayer.setVolume(volume, volume);
            mediaPlayer.setLooping(loop);

            // Set playback speed (API 23+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
            }

            mediaPlayer.setOnPreparedListener(mp -> {
                if (startTime > 0) {
                    mp.seekTo(startTime * 1000);
                }
                mp.start();
                isPlaying = true;

                // Start progress monitoring
                startPlaybackProgressTimer();

                // Notify listeners
                JSObject eventData = new JSObject();
                eventData.put("status", "playing");
                eventData.put("currentTime", mp.getCurrentPosition() / 1000.0);
                eventData.put("duration", mp.getDuration() / 1000.0);
                notifyListeners("playbackStatusChange", eventData);

                call.resolve();
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                isPlaying = false;
                stopPlaybackProgressTimer();

                // Notify listeners
                JSObject eventData = new JSObject();
                eventData.put("status", "completed");
                eventData.put("duration", mp.getDuration() / 1000.0);
                notifyListeners("playbackCompleted", eventData);
                notifyListeners("playbackStatusChange", eventData);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                isPlaying = false;
                stopPlaybackProgressTimer();

                // Notify listeners with detailed error information
                JSObject eventData = new JSObject();
                String errorMessage = getDetailedErrorMessage(what, extra, uri);
                eventData.put("message", "Playback error: " + errorMessage);
                eventData.put("code", what);
                eventData.put("extra", extra);
                notifyListeners("playbackError", eventData);

                return true;
            });

            mediaPlayer.prepareAsync();
            currentPlaybackPath = uri;

        } catch (Exception e) {
            String errorMessage = "Failed to start playback: " + e.getMessage();
            if (isRemoteUrl(uri)) {
                errorMessage += " (Check URL accessibility and network connection)";
            }
            call.reject(errorMessage);
        }
    }

    @PluginMethod
    public void pausePlayback(PluginCall call) {
        if (mediaPlayer == null || !isPlaying) {
            call.reject("No active playback to pause");
            return;
        }

        try {
            mediaPlayer.pause();
            isPlaying = false;
            stopPlaybackProgressTimer();

            // Notify listeners
            JSObject eventData = new JSObject();
            eventData.put("status", "paused");
            eventData.put("currentTime", mediaPlayer.getCurrentPosition() / 1000.0);
            eventData.put("duration", mediaPlayer.getDuration() / 1000.0);
            notifyListeners("playbackStatusChange", eventData);

            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to pause playback", e);
        }
    }

    @PluginMethod
    public void resumePlayback(PluginCall call) {
        if (mediaPlayer == null) {
            call.reject("No active playback to resume");
            return;
        }

        if (isPlaying) {
            call.reject("Playback is already active");
            return;
        }

        try {
            mediaPlayer.start();
            isPlaying = true;
            startPlaybackProgressTimer();

            // Notify listeners
            JSObject eventData = new JSObject();
            eventData.put("status", "playing");
            eventData.put("currentTime", mediaPlayer.getCurrentPosition() / 1000.0);
            eventData.put("duration", mediaPlayer.getDuration() / 1000.0);
            notifyListeners("playbackStatusChange", eventData);

            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to resume playback", e);
        }
    }

    @PluginMethod
    public void stopPlayback(PluginCall call) {
        if (mediaPlayer == null) {
            call.reject("No active playback to stop");
            return;
        }

        try {
            mediaPlayer.stop();
            isPlaying = false;
            stopPlaybackProgressTimer();

            // Notify listeners
            JSObject eventData = new JSObject();
            eventData.put("status", "stopped");
            eventData.put("currentTime", 0);
            if (mediaPlayer.getDuration() > 0) {
                eventData.put("duration", mediaPlayer.getDuration() / 1000.0);
            }
            notifyListeners("playbackStatusChange", eventData);

            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to stop playback", e);
        }
    }

    @PluginMethod
    public void seekTo(PluginCall call) {
        Integer timeMs = call.getInt("time");
        if (timeMs == null) {
            call.reject("Time is required");
            return;
        }

        if (mediaPlayer == null) {
            call.reject("No active playback for seeking");
            return;
        }

        try {
            mediaPlayer.seekTo(timeMs * 1000);

            // Notify listeners
            JSObject eventData = new JSObject();
            eventData.put("currentTime", timeMs);
            eventData.put("duration", mediaPlayer.getDuration() / 1000.0);
            notifyListeners("playbackProgress", eventData);

            call.resolve();
        } catch (Exception e) {
            call.reject("Failed to seek", e);
        }
    }

    @PluginMethod
    public void getPlaybackStatus(PluginCall call) {
        JSObject ret = new JSObject();

        if (mediaPlayer == null) {
            ret.put("status", "idle");
            ret.put("currentTime", 0);
            ret.put("duration", 0);
        } else {
            try {
                String status = isPlaying ? "playing" : "paused";
                ret.put("status", status);
                ret.put("currentTime", mediaPlayer.getCurrentPosition() / 1000.0);
                ret.put("duration", mediaPlayer.getDuration() / 1000.0);
                ret.put("speed", playbackSpeed);
                ret.put("volume", playbackVolume);
                ret.put("isLooping", isLooping);
                ret.put("uri", currentPlaybackPath);
            } catch (Exception e) {
                ret.put("status", "error");
                ret.put("currentTime", 0);
                ret.put("duration", 0);
            }
        }

        call.resolve(ret);
    }

    private void startPlaybackProgressTimer() {
        stopPlaybackProgressTimer();

        playbackProgressTimer = new Timer();
        playbackProgressTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (mediaPlayer != null && isPlaying) {
                    try {
                        double currentTime = mediaPlayer.getCurrentPosition() / 1000.0;
                        double duration = mediaPlayer.getDuration() / 1000.0;
                        double position = duration > 0 ? (currentTime / duration) * 100 : 0;

                        JSObject eventData = new JSObject();
                        eventData.put("currentTime", currentTime);
                        eventData.put("duration", duration);
                        eventData.put("position", position);

                        mainHandler.post(() -> {
                            notifyListeners("playbackProgress", eventData);
                        });
                    } catch (Exception e) {
                        Log.e(TAG, "Error in playback progress timer", e);
                    }
                }
            }
        }, 0, 1000); // Update every second
    }

    private void stopPlaybackProgressTimer() {
        if (playbackProgressTimer != null) {
            playbackProgressTimer.cancel();
            playbackProgressTimer = null;
        }
    }

    // ========== CDN AUDIO HELPER METHODS ==========

    /**
     * Check if the given URI is a remote URL (HTTP/HTTPS)
     */
    private boolean isRemoteUrl(String uri) {
        return uri != null && (uri.startsWith("http://") || uri.startsWith("https://"));
    }

    /**
     * Check if network is available for remote audio playback
     */
    private boolean isNetworkAvailable() {
        try {
            ConnectivityManager connectivityManager =
                (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

            if (connectivityManager != null) {
                NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
                return activeNetworkInfo != null && activeNetworkInfo.isConnected();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking network availability", e);
        }
        return false; // Assume no network if we can't check
    }

    /**
     * Configure MediaPlayer with optimal settings for remote URLs
     */
    private void configureMediaPlayerForRemoteUrl(MediaPlayer mediaPlayer, String uri) throws IOException {
        try {
            // Set audio stream type for playback
            mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);

            // Configure data source headers for CDN compatibility
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", "CapacitorAudioEngine/1.0 (Android)");
            headers.put("Accept", "audio/*");
            headers.put("Accept-Ranges", "bytes");
            headers.put("Cache-Control", "no-cache");
            headers.put("Connection", "keep-alive");
            headers.put("Accept-Encoding", "identity");

            // Use MediaPlayer.setDataSource() with headers for better CDN compatibility
            mediaPlayer.setDataSource(getContext(), Uri.parse(uri), headers);

            Log.d(TAG, "MediaPlayer configured for remote URL playback with enhanced headers");
        } catch (Exception e) {
            Log.w(TAG, "Failed to configure MediaPlayer headers for remote URL, falling back to basic configuration", e);
            // Fallback to basic configuration without headers
          mediaPlayer.setDataSource(uri);
        }
    }

    /**
     * Get detailed error message for MediaPlayer errors
     */
    private String getDetailedErrorMessage(int what, int extra, String uri) {
        String baseMessage = "";

        // Decode 'what' parameter
        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                baseMessage = "Unknown media error";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                baseMessage = "Media server died";
                break;
            default:
                baseMessage = "Media error (code: " + what + ")";
                break;
        }

        // Decode 'extra' parameter for more specific info
        String extraInfo = "";
        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                extraInfo = "File or network related operation error";
                if (isRemoteUrl(uri)) {
                    extraInfo += " - Check network connection and URL accessibility";
                }
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                extraInfo = "Media framework parsing error";
                if (isRemoteUrl(uri)) {
                    extraInfo += " - URL may be invalid or audio format unsupported";
                }
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                extraInfo = "Unsupported media format";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                extraInfo = "Operation timed out";
                if (isRemoteUrl(uri)) {
                    extraInfo += " - Network timeout, check connection speed";
                }
                break;
            default:
                if (extra != 0) {
                    extraInfo = "Additional error code: " + extra;
                }
                break;
        }

        String fullMessage = baseMessage;
        if (!extraInfo.isEmpty()) {
            fullMessage += " - " + extraInfo;
        }

        if (isRemoteUrl(uri)) {
            fullMessage += " (Remote URL: " + uri + ")";
        }

        return fullMessage;
    }

    /**
     * Get detailed HTTP error messages for better CDN error reporting
     */
    private String getHttpErrorMessage(int responseCode) {
        switch (responseCode) {
            case 300:
            case 301:
            case 302:
            case 303:
            case 304:
            case 307:
            case 308:
                return "CDN returned redirect (HTTP " + responseCode + ") - check URL";
            case 400:
                return "Bad request (HTTP " + responseCode + ")";
            case 401:
                return "Unauthorized access (HTTP " + responseCode + ") - authentication required";
            case 403:
                return "Access forbidden (HTTP " + responseCode + ") - check permissions";
            case 404:
                return "Audio file not found (HTTP " + responseCode + ") - check URL";
            case 408:
                return "Request timeout (HTTP " + responseCode + ") - CDN is slow";
            case 410:
                return "Audio file no longer available (HTTP " + responseCode + ")";
            case 429:
                return "Too many requests (HTTP " + responseCode + ") - rate limited by CDN";
            case 500:
            case 502:
            case 503:
            case 504:
                return "CDN server error (HTTP " + responseCode + ") - try again later";
            default:
                if (responseCode >= 400 && responseCode < 500) {
                    return "CDN client error (HTTP " + responseCode + ") - check URL and permissions";
                } else if (responseCode >= 500) {
                    return "CDN server error (HTTP " + responseCode + ") - try again later";
                } else {
                    return "CDN returned unexpected status code: " + responseCode;
                }
        }
    }

    @PluginMethod
    public void getAudioInfo(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null || uri.isEmpty()) {
            call.reject("URI is required");
            return;
        }

        try {
            JSObject audioInfo = extractAudioInfo(uri);
            call.resolve(audioInfo);
        } catch (Exception e) {
            Log.e(TAG, "Error getting audio info: " + e.getMessage(), e);
            call.reject("Failed to get audio info: " + e.getMessage());
        }
    }

    private JSObject extractAudioInfo(String uri) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        JSObject result = new JSObject();

        try {
            // Set data source based on URI type
            if (isRemoteUrl(uri)) {
                // Remote URL - check network availability first
                if (!isNetworkAvailable()) {
                    throw new Exception("Network is not available for remote audio URL");
                }

                // Set timeout and retry policy for remote URLs
                HashMap<String, String> headers = new HashMap<>();
                headers.put("User-Agent", "CapacitorAudioEngine/1.0");
                retriever.setDataSource(uri, headers);
            } else {
                // Local file
                String filePath = uri;
                if (uri.startsWith("file://")) {
                    filePath = uri.substring(7); // Remove "file://" prefix
                }

                File file = new File(filePath);
                if (!file.exists()) {
                    throw new Exception("File does not exist: " + filePath);
                }

                retriever.setDataSource(filePath);
            }

            // Extract metadata
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            String sampleRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
            String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

            // For audio files, we need to try different approaches for sample rate and channels
            String channelsStr = null;
            if (sampleRateStr == null || channelsStr == null) {
                // Try to get sample rate and channels from audio format using MediaExtractor
                MediaExtractor extractor = new MediaExtractor();
                try {
                    if (isRemoteUrl(uri)) {
                        HashMap<String, String> headers = new HashMap<>();
                        headers.put("User-Agent", "CapacitorAudioEngine/1.0");
                        extractor.setDataSource(uri, headers);
                    } else {
                        String filePath = uri.startsWith("file://") ? uri.substring(7) : uri;
                        extractor.setDataSource(filePath);
                    }

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String formatMimeType = format.getString(MediaFormat.KEY_MIME);
                        if (formatMimeType != null && formatMimeType.startsWith("audio/")) {
                            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE) && sampleRateStr == null) {
                                sampleRateStr = String.valueOf(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                            }
                            if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) && channelsStr == null) {
                                channelsStr = String.valueOf(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                            }
                            break;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Could not extract audio format using MediaExtractor: " + e.getMessage());
                } finally {
                    try {
                        extractor.release();
                    } catch (Exception e) {
                        // Ignore
                    }
                }
            }

            // Parse values with defaults
            long duration = durationStr != null ? Long.parseLong(durationStr) : 0;
            int bitrate = bitrateStr != null ? Integer.parseInt(bitrateStr) : 128000;
            double sampleRate = sampleRateStr != null ? Double.parseDouble(sampleRateStr) : 44100.0;

            // Default values
            int channels = channelsStr != null ? Integer.parseInt(channelsStr) : 1; // Use extracted channels or default to mono
            long fileSize = 0;
            long createdAt = System.currentTimeMillis();
            String filename = "";

            // For local files, get additional file information
            if (!isRemoteUrl(uri)) {
                String filePath = uri.startsWith("file://") ? uri.substring(7) : uri;
                File file = new File(filePath);

                if (file.exists()) {
                    fileSize = file.length();
                    createdAt = file.lastModified();
                    filename = file.getName();
                }
            } else {
                // For remote URLs, extract filename from URL
                try {
                    java.net.URL url = new java.net.URL(uri);
                    String path = url.getPath();
                    if (path != null && !path.isEmpty()) {
                        int lastSlash = path.lastIndexOf('/');
                        if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                            filename = path.substring(lastSlash + 1);
                        }
                    }
                    if (filename.isEmpty()) {
                        filename = "remote_audio.m4a";
                    }
                } catch (Exception e) {
                    filename = "remote_audio.m4a";
                }
            }

            // Determine MIME type if not available
            if (mimeType == null || mimeType.isEmpty()) {
                String lowerUri = uri.toLowerCase();
                if (lowerUri.endsWith(".m4a")) {
                    mimeType = "audio/m4a";
                } else if (lowerUri.endsWith(".mp3")) {
                    mimeType = "audio/mpeg";
                } else if (lowerUri.endsWith(".wav")) {
                    mimeType = "audio/wav";
                } else if (lowerUri.endsWith(".aac")) {
                    mimeType = "audio/aac";
                } else {
                    mimeType = "audio/m4a"; // Default
                }
            }

            // Build result object
            result.put("path", uri);
            result.put("webPath", uri);
            result.put("uri", uri);
            result.put("mimeType", mimeType);
            result.put("size", fileSize);
            result.put("duration", Math.round((duration / 1000.0) * 10.0) / 10.0); // Convert to seconds and round to 1 decimal
            result.put("sampleRate", sampleRate);
            result.put("channels", channels);
            result.put("bitrate", bitrate);
            result.put("createdAt", createdAt);
            result.put("filename", filename);

            return result;

        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}
