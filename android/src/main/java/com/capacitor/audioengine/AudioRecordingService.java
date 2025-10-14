package com.capacitor.audioengine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service to enable background audio recording.
 * This service ensures that recording continues even when the app is in the background.
 */
public class AudioRecordingService extends Service {
    private static final String TAG = "AudioRecordingService";
    private static final String CHANNEL_ID = "audio_recording_channel";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AudioRecordingService created");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AudioRecordingService started");

        // Create notification for foreground service
        Notification notification = createNotification();
        startForeground(NOTIFICATION_ID, notification);

        // the recording should stop when app is closed
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // We don't need binding
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        Log.d(TAG, "App task removed - stopping recording service");
        // Stop the service when the app is force closed or removed from recent apps
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "AudioRecordingService destroyed");
        // we remove the foreground service notification
        stopForeground(true);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID,
            "Audio Recording",
            NotificationManager.IMPORTANCE_LOW // Low importance to avoid sound/vibration
        );
        channel.setDescription("Recording audio in background");
        channel.setShowBadge(false);

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        // Create intent to open the app when notification is tapped
        Intent notificationIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = null;

        if (notificationIntent != null) {
            pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
            );
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Audio")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Make notification persistent
            .setShowWhen(false);

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent);
        }

        return builder.build();
    }
}

