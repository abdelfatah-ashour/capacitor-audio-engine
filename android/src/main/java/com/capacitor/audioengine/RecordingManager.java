package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import android.content.pm.PackageManager;

import java.io.File;
import java.util.Timer;
import java.util.TimerTask;

class RecordingManager implements AudioManager.OnAudioFocusChangeListener {
    interface RecordingCallback {
        void onStatusChanged(String status);
        void onError(String message);
        void onDurationChanged(double duration);
    }

    private static final String TAG = "RecordingManager";

    private final RecordingCallback callback;
    private final Context context;
    private final Handler mainHandler;

    private MediaRecorder mediaRecorder;

    // Audio focus management
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    // Duration monitoring
    private Timer durationTimer;
    private double currentDuration = 0.0;
    private volatile boolean isDurationMonitoring = false;
    private volatile boolean isDurationPaused = false;

    // Wave level monitoring removed - handled by WaveLevelEmitter
    private boolean isRecording = false;
    private boolean isPaused = false;
    private String currentOutputPath;

    private StartOptions lastOptions;

    RecordingManager(Context context, RecordingCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());

        // Initialize audio manager
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Create audio focus request for recording
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                    new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
            )
            .setOnAudioFocusChangeListener(this)
            .build();
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void startRecording(StartOptions options) {
        if (isRecording) return;
        try {
            // Explicit permission check to avoid SecurityException at runtime
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (callback != null) callback.onError("RECORD_AUDIO permission not granted");
                return;
            }

            // Request audio focus for recording
            if (!requestAudioFocus()) {
                Log.w(TAG, "Failed to gain audio focus, but continuing with recording");
                // Continue anyway - don't fail recording just because of audio focus
            }

            // Start foreground service for background recording
            try {
                Intent serviceIntent = new Intent(context, AudioRecordingService.class);
                ContextCompat.startForegroundService(context, serviceIntent);
                Log.d(TAG, "Foreground service started for recording");
            } catch (Exception e) {
                Log.w(TAG, "Failed to start foreground service", e);
                // Continue with recording even if service fails to start
            }

            String outputPath = options.path;
            lastOptions = options;

            // Reset duration for fresh recordings (not for resume after reset)
            // We can detect this by checking if we're not currently recording/paused
            if (!isPaused) {
                synchronized (this) {
                    currentDuration = 0.0;
                }
            }

            // Prepare output path
            if (outputPath != null && !outputPath.isEmpty()) {
                currentOutputPath = getNormalizedPath(outputPath);
                File outFile = new File(currentOutputPath);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
            } else {
                // Use same directory as Capacitor Filesystem Directory.Data
                // On Android, this maps to the app's data directory (internal storage)
                String dataDir = context.getFilesDir().getAbsolutePath();
                currentOutputPath = dataDir + "/recording_" + System.currentTimeMillis() + ".m4a";
                File outFile = new File(currentOutputPath);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                if (outFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.delete();
                }
            }
            // Configure MediaRecorder using unified configuration
            // Reuse pre-warmed recorder if available, otherwise create new one
            if (mediaRecorder == null) {
                Log.d(TAG, "Creating new MediaRecorder");
                mediaRecorder = new MediaRecorder();
                setBestAudioSource(mediaRecorder);
            } else {
                // Use the pre-warmed recorder (it's already created and has audio source set)
                Log.d(TAG, "Reusing pre-warmed MediaRecorder");
                // No need to reset or reconfigure audio source - it's already set
            }

            configureRecorderCommon(mediaRecorder, currentOutputPath);
            try {
                mediaRecorder.prepare();
                mediaRecorder.start();
            } catch (SecurityException se) {
                Log.e(TAG, "SecurityException starting MediaRecorder", se);
                if (callback != null) callback.onError("SecurityException: RECORD_AUDIO denied or restricted");
                cleanupMediaRecorder();
                isRecording = false;
                return;
            }

            isRecording = true;
            isPaused = false;

            // Start duration monitoring only after successful start()
            try {
                startDurationMonitoring();

                if (callback != null) {
                    callback.onStatusChanged("recording");
                }
            } catch (Exception monitoringError) {
                Log.e(TAG, "Failed to start monitoring after recording start", monitoringError);
                // Ensure any partially started timers are stopped
                try { stopDurationMonitoring(); } catch (Exception ignored) {}
                // Cleanup recorder and reset state
                cleanupMediaRecorder();
                isRecording = false;
                isPaused = false;
                if (callback != null) callback.onError("Failed to start monitoring: " + monitoringError.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start recording", e);
            if (callback != null) callback.onError(e.getMessage());
            cleanupMediaRecorder();
            isRecording = false;
        }
    }
    private String getNormalizedPath(String outputPath) {
        String baseDir = context.getFilesDir().getAbsolutePath();
        String normalizedPath;
        if (outputPath.startsWith("file://")) {
            normalizedPath = outputPath.substring(7);
        } else if (outputPath.startsWith("/")) {
            normalizedPath = baseDir + "/" + outputPath.substring(1);
        } else {
            normalizedPath = baseDir + "/" + outputPath;
        }
        return normalizedPath;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void stopRecording() {
        if (!isRecording) return;
        try {
            if (isPaused && mediaRecorder != null) {
                // If paused, some devices require a resume before stop; ignore errors
                try { mediaRecorder.resume(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        cleanupMediaRecorder();

        // Stop duration monitoring
        stopDurationMonitoring();

        // Stop foreground service
        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            context.stopService(serviceIntent);
            Log.d(TAG, "Foreground service stopped");
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop foreground service", e);
        }

        // Abandon audio focus
        abandonAudioFocus();

        isRecording = false;
        isPaused = false;
        if (callback != null) callback.onStatusChanged("idle");
    }

    /**
     * Stop recording and wait for the file to be fully written to disk
     * @return The path to the recording file, or null if failed
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    String stopRecordingAndWaitForFile() {
        if (!isRecording) return currentOutputPath;

        String filePath = currentOutputPath;

        try {
            if (isPaused && mediaRecorder != null) {
                // If paused, some devices require a resume before stop; ignore errors
                try { mediaRecorder.resume(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        // Stop the MediaRecorder and ensure it's properly flushed
        cleanupMediaRecorderAndWaitForFile();

        // Stop duration monitoring
        stopDurationMonitoring();

        // Stop foreground service
        try {
            Intent serviceIntent = new Intent(context, AudioRecordingService.class);
            context.stopService(serviceIntent);
            Log.d(TAG, "Foreground service stopped");
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop foreground service", e);
        }

        // Abandon audio focus
        abandonAudioFocus();

        isRecording = false;
        isPaused = false;
        if (callback != null) callback.onStatusChanged("idle");

        return filePath;
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void pauseRecording() {
        if (!isRecording || isPaused) return;
        try {
            try {
                mediaRecorder.pause();
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException pausing MediaRecorder", se);
                if (callback != null) callback.onError("SecurityException: cannot pause recording");
                return;
            }
            isPaused = true;

            // Pause duration monitoring
            pauseDurationMonitoring();

            if (callback != null) callback.onStatusChanged("paused");
        } catch (Exception e) {
            Log.w(TAG, "Error pausing recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void resumeRecording() {
        if (!isRecording || !isPaused) return;
        try {
            if (mediaRecorder != null) {
                try {
                    // Prefer resume when supported (normal pause/resume flow)
                    mediaRecorder.resume();
                } catch (SecurityException se) {
                    Log.w(TAG, "SecurityException resuming MediaRecorder", se);
                    if (callback != null) callback.onError("SecurityException: cannot resume recording");
                    return;
                } catch (IllegalStateException ise) {
                    // If resume() isn't valid (e.g., recorder was only prepared by reset), start() instead
                    try {
                        mediaRecorder.start();
                    } catch (Exception startEx) {
                        Log.w(TAG, "Failed to start MediaRecorder on resume after reset", startEx);
                        if (callback != null) callback.onError("Failed to start recording after reset: " + startEx.getMessage());
                        return;
                    }
                }
            } else {
                // Re-create and resume recording if needed on older devices
                if (lastOptions == null) {
                    lastOptions = new StartOptions();
                    lastOptions.path = currentOutputPath;
                }
                startRecording(lastOptions);
                isPaused = false;
                return;
            }

            isPaused = false;

            // Resume duration monitoring
            resumeDurationMonitoring();

            if (callback != null) callback.onStatusChanged("recording");
        } catch (Exception e) {
            Log.w(TAG, "Error resuming recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    void resetRecording() {
        if (!isRecording) return;
        try {
            // Pause logical state
            isPaused = true;

            // Dispose current recorder instance
            cleanupMediaRecorder();

            // Stop monitoring and reset duration to 0
            stopDurationMonitoring();
            synchronized (this) {
                currentDuration = 0.0;
            }

            // Create a fresh recorder configured for the same path, but remain paused until resume
            prepareMediaRecorderForCurrentPath();

            if (callback != null) callback.onStatusChanged("paused");
        } catch (Exception e) {
            Log.w(TAG, "Error resetting recording", e);
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    private void cleanupMediaRecorder() {
        try {
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (IllegalStateException ise) {
                    Log.w(TAG, "MediaRecorder stop called in illegal state", ise);
                } catch (Exception stopEx) {
                    Log.w(TAG, "MediaRecorder stop error", stopEx);
                }
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) {}
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {}
                mediaRecorder = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "cleanupMediaRecorder error", e);
        }
    }

    /**
     * Cleanup MediaRecorder and wait for file to be fully written to disk
     * Optimized for faster cleanup after long recordings
     */
    private void cleanupMediaRecorderAndWaitForFile() {
        try {
            if (mediaRecorder != null) {
                // Stop the MediaRecorder - this blocks until it's done
                try {
                    Log.d(TAG, "Stopping MediaRecorder...");
                    mediaRecorder.stop();
                    Log.d(TAG, "MediaRecorder stopped successfully");

                    // MediaRecorder.stop() is synchronous and should flush all data
                    // Reduced sleep time for faster cleanup after long recordings
                    Thread.sleep(50);

                } catch (IllegalStateException ise) {
                    Log.w(TAG, "MediaRecorder stop called in illegal state", ise);
                } catch (RuntimeException re) {
                    Log.w(TAG, "MediaRecorder stop runtime error", re);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while waiting for file", ie);
                }

                // Reset and release immediately - don't wait for file verification
                try {
                    mediaRecorder.reset();
                } catch (Exception ignored) {}
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {}
                mediaRecorder = null;

                // Verify the file is ready asynchronously to avoid blocking
                if (currentOutputPath != null) {
                    verifyFileAsync(currentOutputPath);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in cleanupMediaRecorderAndWaitForFile", e);
        }
    }

    /**
     * Verify file existence and size asynchronously to avoid blocking
     */
    private void verifyFileAsync(String filePath) {
        new Thread(() -> {
            try {
                // Wait a bit for file system to catch up
                Thread.sleep(100);

                File outputFile = new File(filePath);
                if (outputFile.exists() && outputFile.length() > 0) {
                    Log.d(TAG, "Recording file verified: " + outputFile.length() + " bytes");
                } else {
                    Log.w(TAG, "Recording file not ready: exists=" + outputFile.exists() +
                          ", size=" + outputFile.length());
                }
            } catch (Exception e) {
                Log.w(TAG, "Error verifying file asynchronously", e);
            }
        }).start();
    }

    private void prepareMediaRecorderForCurrentPath() {
        try {
            if (currentOutputPath == null) return;
            File outFile = new File(currentOutputPath);
            File parent = outFile.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (outFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outFile.delete();
            }

            mediaRecorder = new MediaRecorder();
            setBestAudioSource(mediaRecorder);
            configureRecorderCommon(mediaRecorder, currentOutputPath);
            mediaRecorder.prepare();
            // Do not start here; remain paused until resumeRecording()
        } catch (Exception e) {
            Log.w(TAG, "prepareMediaRecorderForCurrentPath error", e);
        }
    }

    private void configureRecorderCommon(MediaRecorder recorder, String outputPath) {
        try {
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            int bitrate = AudioEngineConfig.Recording.DEFAULT_BITRATE;
            recorder.setAudioEncodingBitRate(bitrate);
            // Recording configuration
            int sampleRate = AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
            recorder.setAudioSamplingRate(sampleRate);
            int channels = AudioEngineConfig.Recording.DEFAULT_CHANNELS;
            recorder.setAudioChannels(channels);
            recorder.setOutputFile(outputPath);
        } catch (Exception e) {
            Log.w(TAG, "configureRecorderCommon error", e);
        }
    }

        private void setBestAudioSource(MediaRecorder recorder) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            } catch (Exception e) {
                try {
                    recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION);
                } catch (Exception inner1) {
                    try {
                        recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    } catch (Exception inner2) {
                        Log.w(TAG, "Failed to set audio source", inner2);
                    }
                }
            }
        }

    // Duration monitoring methods
    private void startDurationMonitoring() {
        stopDurationMonitoring();
        Log.d(TAG, "Starting duration monitoring for recording");

        isDurationMonitoring = true;
        isDurationPaused = false;
        // For fresh recordings, currentDuration should already be 0.0
        // For resumed recordings after reset, we preserve the existing currentDuration

        durationTimer = new Timer();
        durationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isDurationPaused && isDurationMonitoring) {
                    double duration;
                    synchronized (RecordingManager.this) {
                        currentDuration += 1.0;
                        duration = currentDuration;
                    }
                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onDurationChanged(duration);
                        }
                    });
                }
            }
        }, 1000, 1000); // Start after 1 second, repeat every 1 second
    }    private void stopDurationMonitoring() {
        if (durationTimer != null) {
            durationTimer.cancel();
            durationTimer = null;
        }
        isDurationMonitoring = false;
        isDurationPaused = false;
        Log.d(TAG, "Duration monitoring stopped");
    }

    private void pauseDurationMonitoring() {
        isDurationPaused = true;
        Log.d(TAG, "Duration monitoring paused");
    }

    private void resumeDurationMonitoring() {
        if (!isDurationMonitoring) {
            // If monitoring was completely stopped (e.g., after reset), restart it
            // but preserve the current duration
            Log.d(TAG, "Restarting duration monitoring after reset, preserving duration: " + currentDuration);
            startDurationMonitoring(); // Will preserve existing duration
        } else {
            // Just resume paused monitoring
            isDurationPaused = false;
        }
        Log.d(TAG, "Duration monitoring resumed");
    }

    // Wave level monitoring removed - handled by WaveLevelEmitter

    // Audio focus management
    private boolean requestAudioFocus() {
        if (audioManager == null) return false;

        try {
            int result;
            if (audioFocusRequest != null) {
                result = audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                return false;
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            if (hasAudioFocus) {
                Log.d(TAG, "Audio focus granted for recording");
            } else {
                Log.w(TAG, "Audio focus denied for recording");
            }
            return hasAudioFocus;
        } catch (Exception e) {
            Log.e(TAG, "Error requesting audio focus", e);
            return false;
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null || !hasAudioFocus) return;

        try {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            }
            hasAudioFocus = false;
            Log.d(TAG, "Audio focus abandoned");
        } catch (Exception e) {
            Log.e(TAG, "Error abandoning audio focus", e);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        Log.d(TAG, "Audio focus changed: " + focusChange);

        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // Audio focus gained - we can continue recording
                Log.d(TAG, "Audio focus gained - recording can continue");
                // Don't auto-resume if user explicitly paused
                // Just log that we have focus again
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Permanent loss of audio focus - another app took over
                Log.w(TAG, "Audio focus lost permanently - another app is using audio");
                // For recording, we should continue in background with foreground service
                // Don't automatically pause - let the recording continue
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Temporary loss of audio focus - e.g., phone call
                Log.w(TAG, "Audio focus lost temporarily - transient interruption");
                // For recording, we want to keep recording in background
                // Don't pause - the foreground service will keep it alive
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Another app needs audio but we can "duck" (lower volume)
                // For recording, this doesn't affect us - continue recording
                Log.d(TAG, "Audio focus loss can duck - continuing recording");
                break;

            default:
                Log.w(TAG, "Unknown audio focus change: " + focusChange);
                break;
        }
    }

    static class StartOptions {
        String path;
    }

    StatusInfo getStatus() {
        synchronized (this) {
            String statusString;
            if (!isRecording) {
                statusString = "idle";
            } else if (isPaused) {
                statusString = "paused";
            } else {
                statusString = "recording";
            }
            return new StatusInfo(statusString, currentDuration, currentOutputPath);
        }
    }

    record StatusInfo(String status, double duration, String path) {}

    /**
     * Pre-warm MediaRecorder for faster subsequent recordings
     * This helps reduce delay when starting new recordings after long sessions
     */
    public void preWarmRecorder() {
        try {
            if (mediaRecorder == null && !isRecording) {
                Log.d(TAG, "Pre-warming MediaRecorder for faster subsequent recordings");
                // Just create and configure the MediaRecorder, but don't prepare it yet
                // This avoids the IllegalStateException when reusing
                mediaRecorder = new MediaRecorder();
                setBestAudioSource(mediaRecorder);
                // Don't call configureRecorderCommon or prepare yet
                // This will be done in startRecording when we have the actual output path
                Log.d(TAG, "MediaRecorder pre-warmed successfully (ready for configuration)");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to pre-warm MediaRecorder", e);
            // Clean up on failure
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.release();
                } catch (Exception ignored) {}
                mediaRecorder = null;
            }
        }
    }

}
