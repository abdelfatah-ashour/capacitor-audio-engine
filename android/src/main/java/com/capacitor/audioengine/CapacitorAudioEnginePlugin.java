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
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;
import androidx.core.app.ActivityCompat;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

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

    @Override
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);

        PluginCall savedCall = getSavedCall();
        if (savedCall == null) {
            return;
        }

        // Check current permission states
        boolean audioGranted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED;

        boolean notificationGranted = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationGranted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        }

        // If audio permission was just granted but notification permission is still needed
        if (audioGranted && !notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Don't resolve yet, request notification permission
            requestPermissionForAlias("notifications", savedCall, "permissionCallback");
            return;
        }

        JSObject result = new JSObject();
        result.put("granted", audioGranted && notificationGranted);
        result.put("audioPermission", audioGranted);
        result.put("notificationPermission", notificationGranted);
        savedCall.resolve(result);
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
        }

        return info;
    }

    // Event emission methods
    private void emitDurationChange() {
        JSObject data = new JSObject();
        JSObject payload = new JSObject();
        payload.put("duration", currentDuration);
        data.put("eventName", "durationChange");
        data.put("payload", payload);
        notifyListeners("durationChange", data);
    }

    private void emitError(JSObject errorData) {
        JSObject data = new JSObject();
        data.put("eventName", "error");
        data.put("payload", errorData);
        notifyListeners("error", data);
    }

    private void emitInterruption(String message) {
        JSObject data = new JSObject();
        JSObject payload = new JSObject();
        payload.put("message", message);
        data.put("eventName", "recordingInterruption");
        data.put("payload", payload);
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
}