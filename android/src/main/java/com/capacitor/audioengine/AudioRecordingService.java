package com.capacitor.audioengine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;

/**
 * Foreground service for continuous audio recording even when screen is locked
 * Handles screen lock/unlock events to maintain proper recording state
 */
public class AudioRecordingService extends Service {
    private static final String TAG = "AudioRecordingService";
    private static final String CHANNEL_ID = "AudioRecordingChannel";
    private static final int NOTIFICATION_ID = 1001;

    public interface RecordingServiceListener {
        void onScreenLocked();
        void onScreenUnlocked();
        void onRecordingStateChanged(boolean isRecording);
    }

    private final IBinder binder = new LocalBinder();
    private RecordingServiceListener listener;
    private BroadcastReceiver screenStateReceiver;
    private boolean isScreenLocked = false;
    private boolean isRecordingActive = false;

    public class LocalBinder extends Binder {
        public AudioRecordingService getService() {
            return AudioRecordingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "AudioRecordingService created");
        createNotificationChannel();
        registerScreenStateReceiver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "AudioRecordingService started");

        String action = intent != null ? intent.getAction() : null;
        if ("START_RECORDING".equals(action)) {
            startForegroundRecording();
        } else if ("STOP_RECORDING".equals(action)) {
            stopForegroundRecording();
        }

        return START_STICKY; // Restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "AudioRecordingService destroyed");
        unregisterScreenStateReceiver();
        super.onDestroy();
    }

    public void setRecordingServiceListener(RecordingServiceListener listener) {
        this.listener = listener;
    }

    public void startForegroundRecording() {
        Log.d(TAG, "Starting foreground recording service");
        isRecordingActive = true;

        Notification notification = createRecordingNotification();
        startForeground(NOTIFICATION_ID, notification);

        if (listener != null) {
            listener.onRecordingStateChanged(true);
        }
    }

    public void stopForegroundRecording() {
        Log.d(TAG, "Stopping foreground recording service");
        isRecordingActive = false;

        stopForeground(true);
        stopSelf();

        if (listener != null) {
            listener.onRecordingStateChanged(false);
        }
    }

    public boolean isRecordingActive() {
        return isRecordingActive;
    }

    public boolean isScreenLocked() {
        return isScreenLocked;
    }

    private void createNotificationChannel() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);

        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Audio Recording",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ongoing audio recording");
            channel.setSound(null, null); // Silent
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createRecordingNotification() {
        // Create intent to return to app when notification is tapped
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Recording")
            .setContentText("Recording in progress...")
            .setSmallIcon(android.R.drawable.ic_media_play) // Use system icon
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build();
    }

    private void registerScreenStateReceiver() {
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    Log.d(TAG, "Screen locked - maintaining recording in background");
                    isScreenLocked = true;
                    if (listener != null) {
                        listener.onScreenLocked();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    Log.d(TAG, "Screen unlocked - recording continues");
                    isScreenLocked = false;
                    if (listener != null) {
                        listener.onScreenUnlocked();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenStateReceiver, filter);

        Log.d(TAG, "Screen state receiver registered");
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver != null) {
            try {
                unregisterReceiver(screenStateReceiver);
                Log.d(TAG, "Screen state receiver unregistered");
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering screen state receiver", e);
            }
            screenStateReceiver = null;
        }
    }
}