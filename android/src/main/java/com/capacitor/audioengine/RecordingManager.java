package com.capacitor.audioengine;

import android.Manifest;
import android.content.Context;
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

class RecordingManager {
    interface RecordingCallback {
        void onStatusChanged(String status);
        void onError(String message);
        void onDurationChanged(double duration);
        void onWaveLevel(double level, long timestamp);
    }

    private static final String TAG = "RecordingManager";

    private final RecordingCallback callback;
    private final Context context;
    private final Handler mainHandler;

    private MediaRecorder mediaRecorder;

    // Duration monitoring
    private Timer durationTimer;
    private double currentDuration = 0.0;
    private volatile boolean isDurationMonitoring = false;
    private volatile boolean isDurationPaused = false;

    // Wave level monitoring
    private Timer waveLevelTimer;
    private volatile boolean isWaveLevelMonitoring = false;
    private volatile boolean isWaveLevelPaused = false;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private String currentOutputPath;

    // Recording configuration
    private final int sampleRate = AudioEngineConfig.Recording.DEFAULT_SAMPLE_RATE;
    private final int channels = AudioEngineConfig.Recording.DEFAULT_CHANNELS;
    private final int bitrate = AudioEngineConfig.Recording.DEFAULT_BITRATE;

    private StartOptions lastOptions;

    RecordingManager(Context context, RecordingCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
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
            String outputPath = options.path;
            lastOptions = options;

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
            mediaRecorder = new MediaRecorder();
            setBestAudioSource(mediaRecorder);
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

            // Start duration and wave level monitoring only after successful start()
            try {
                startDurationMonitoring();
                startWaveLevelMonitoring();

                if (callback != null) {
                    callback.onStatusChanged("recording");
                }
            } catch (Exception monitoringError) {
                Log.e(TAG, "Failed to start monitoring after recording start", monitoringError);
                // Ensure any partially started timers are stopped
                try { stopDurationMonitoring(); } catch (Exception ignored) {}
                try { stopWaveLevelMonitoring(); } catch (Exception ignored) {}
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

        // Stop duration and wave level monitoring
        stopDurationMonitoring();
        stopWaveLevelMonitoring();

        isRecording = false;
        isPaused = false;
        if (callback != null) callback.onStatusChanged("stopped");
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

            // Pause duration and wave level monitoring
            pauseDurationMonitoring();
            pauseWaveLevelMonitoring();

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

            // Resume duration and wave level monitoring
            resumeDurationMonitoring();
            resumeWaveLevelMonitoring();

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

            // Reset monitoring counters to 0
            stopDurationMonitoring();
            stopWaveLevelMonitoring();
            synchronized (this) { currentDuration = 0.0; }

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
            recorder.setAudioEncodingBitRate(bitrate);
            recorder.setAudioSamplingRate(sampleRate);
            recorder.setAudioChannels(channels);
            recorder.setOutputFile(outputPath);
        } catch (Exception e) {
            Log.w(TAG, "configureRecorderCommon error", e);
        }
    }

    private void setBestAudioSource(MediaRecorder recorder) {
        try {
            // Prefer UNPROCESSED for full-band music when available (API 24+), else MIC
            int source;
            try {
                source = MediaRecorder.AudioSource.UNPROCESSED; // may throw on some API levels/devices
            } catch (Throwable t) {
                source = MediaRecorder.AudioSource.MIC;
            }
            recorder.setAudioSource(source);
        } catch (Exception e) {
            try {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } catch (Exception inner) {
                Log.w(TAG, "Failed to set audio source", inner);
            }
        }
    }

    FormatInfo getFormatInfo() {
        String path = currentOutputPath;
        if (path == null) {
            // Fallback to a default path if currentOutputPath is null
            String dataDir = context.getFilesDir().getAbsolutePath();
            path = dataDir + "/recording_" + System.currentTimeMillis() + ".m4a";
        }
        return new FormatInfo(sampleRate, channels, "aac", "audio/mp4", bitrate, path);
    }

    // Duration monitoring methods
    private void startDurationMonitoring() {
        stopDurationMonitoring();
        Log.d(TAG, "Starting duration monitoring for recording");

        isDurationMonitoring = true;
        isDurationPaused = false;
        synchronized (this) {
            currentDuration = 0.0;
        }

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
    }

    private void stopDurationMonitoring() {
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
        isDurationPaused = false;
        Log.d(TAG, "Duration monitoring resumed");
    }

    // Wave level monitoring methods
    private void startWaveLevelMonitoring() {
        stopWaveLevelMonitoring();
        Log.d(TAG, "Starting wave level monitoring for recording");

        isWaveLevelMonitoring = true;
        isWaveLevelPaused = false;

        waveLevelTimer = new Timer();
        int waveLevelEmissionIntervalMs = 200;
        waveLevelTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isWaveLevelPaused && isWaveLevelMonitoring && mediaRecorder != null) {
                    try {
                        double normalizedLevel = calculateWaveLevel();

                        mainHandler.post(() -> {
                            if (callback != null) {
                                callback.onWaveLevel(normalizedLevel, System.currentTimeMillis());
                            }
                        });
                    } catch (Exception e) {
                        Log.w(TAG, "Error calculating wave level", e);
                    }
                }
            }
        }, waveLevelEmissionIntervalMs, waveLevelEmissionIntervalMs);
    }

    private void stopWaveLevelMonitoring() {
        if (waveLevelTimer != null) {
            waveLevelTimer.cancel();
            waveLevelTimer = null;
        }
        isWaveLevelMonitoring = false;
        isWaveLevelPaused = false;
        Log.d(TAG, "Wave level monitoring stopped");
    }

    private void pauseWaveLevelMonitoring() {
        isWaveLevelPaused = true;
        Log.d(TAG, "Wave level monitoring paused");
    }

    private void resumeWaveLevelMonitoring() {
        isWaveLevelPaused = false;
        Log.d(TAG, "Wave level monitoring resumed");
    }

    /**
     * Calculate wave level using MediaRecorder.getMaxAmplitude()
     * Normalized between 0.0 and 1.0 where 1.0 maps to 32767
     */
    private double calculateWaveLevel() {
        if (mediaRecorder == null || !isRecording) {
            return 0.0;
        }

        try {
            int amp = 0;
            try { amp = mediaRecorder.getMaxAmplitude(); } catch (Exception ignored) {}
            if (amp <= 0) return 0.0;
            return Math.min(1.0, amp / 32767.0);
        } catch (Exception e) {
            Log.w(TAG, "Error calculating wave level", e);
            return 0.0;
        }
    }

    static class StartOptions {
        String path;
    }

    record FormatInfo(int sampleRate, int channels, String encoding, String mimeType, int bitrate, String path) {}

    StatusInfo getStatus() {
        synchronized (this) {
            String statusString;
            if (!isRecording) {
                statusString = "stopped";
            } else if (isPaused) {
                statusString = "paused";
            } else {
                statusString = "recording";
            }
            return new StatusInfo(statusString, currentDuration, currentOutputPath);
        }
    }

    record StatusInfo(String status, double duration, String path) {}
}
