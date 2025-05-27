package com.capacitor.audioengine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * Background service for long-running audio recording operations.
 * This service ensures that recording can continue even when the app is in the background.
 *
 * Features:
 * - Foreground service for uninterrupted recording
 * - Handles Android 10+ background restrictions
 * - Manages recording state across app lifecycle
 */
public class AudioRecordingService extends Service {
    private static final String TAG = "AudioRecordingService";
    private static final String CHANNEL_ID = "audio_recording_channel";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START_RECORDING = "com.capacitor.audioengine.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.capacitor.audioengine.STOP_RECORDING";
    public static final String ACTION_PAUSE_RECORDING = "com.capacitor.audioengine.PAUSE_RECORDING";
    public static final String ACTION_RESUME_RECORDING = "com.capacitor.audioengine.RESUME_RECORDING";

    private boolean isRecordingActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AudioRecordingService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "Service received action: " + action);

            switch (action) {
                case ACTION_START_RECORDING:
                    startForegroundRecording();
                    break;
                case ACTION_STOP_RECORDING:
                    stopRecording();
                    break;
                case ACTION_PAUSE_RECORDING:
                    pauseRecording();
                    break;
                case ACTION_RESUME_RECORDING:
                    resumeRecording();
                    break;
            }
        }

        // Return START_NOT_STICKY so service doesn't restart if killed during recording
        // The plugin will manage service lifecycle appropriately
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // This service doesn't provide binding
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AudioRecordingService destroyed");
        isRecordingActive = false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Recording Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Handles background audio recording operations");
            channel.setSound(null, null); // No sound for recording notifications
            channel.enableVibration(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundRecording() {
        Log.d(TAG, "Starting foreground recording service");
        isRecordingActive = true;

        Notification notification = createRecordingNotification("Recording audio...");

        // Use FOREGROUND_SERVICE_TYPE_MICROPHONE for Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+) - all versions use the same approach
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            // Android 9 and below
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void pauseRecording() {
        Log.d(TAG, "Pausing recording service");
        if (isRecordingActive) {
            Notification notification = createRecordingNotification("Recording paused");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private void resumeRecording() {
        Log.d(TAG, "Resuming recording service");
        if (isRecordingActive) {
            Notification notification = createRecordingNotification("Recording resumed...");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    private void stopRecording() {
        Log.d(TAG, "Stopping recording service");
        isRecordingActive = false;
        stopForeground(true);
        stopSelf();
    }

    private Notification createRecordingNotification(String contentText) {
        // Create intent to return to app when notification is tapped
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setPackage(getPackageName());

        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Recording")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // Use system microphone icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Cannot be dismissed by user
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build();
    }

    public boolean isRecordingActive() {
        return isRecordingActive;
    }
}
