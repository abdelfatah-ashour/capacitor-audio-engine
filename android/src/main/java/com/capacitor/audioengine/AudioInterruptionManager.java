package com.capacitor.audioengine;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Comprehensive audio interruption handling for Android recording sessions.
 * Handles:
 * - Audio Focus management (music apps, notifications, etc.)
 * - Phone call interruptions (incoming/outgoing calls)
 * - System audio interruptions (alarms, notifications)
 * - Headphone connect/disconnect events
 * Provides similar functionality to iOS AVAudioSession interruption handling.
 */
public class AudioInterruptionManager {
    private static final String TAG = "AudioInterruptionManager";

    public interface InterruptionCallback {
        void onInterruptionBegan(InterruptionType type);
        void onInterruptionEnded(InterruptionType type, boolean shouldResume);
        void onAudioRouteChanged(String reason);
    }

    public enum InterruptionType {
        PHONE_CALL("Phone Call"),
        AUDIO_FOCUS_LOSS("Audio Focus Loss"),
        AUDIO_FOCUS_LOSS_TRANSIENT("Audio Focus Loss Transient"),
        AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK("Audio Focus Loss Transient Can Duck"),
        SYSTEM_NOTIFICATION("System Notification"),
        HEADPHONE_DISCONNECT("Headphone Disconnect"),
        UNKNOWN("Unknown");

        private final String description;

        InterruptionType(String description) {
            this.description = description;
        }

        @NonNull
        @Override
        public String toString() {
            return description;
        }
    }

    private final Context context;
    private final AudioManager audioManager;
    private final TelephonyManager telephonyManager;
    private InterruptionCallback callback;

    // Audio Focus Management
    private AudioFocusRequest audioFocusRequest;
    private final AtomicBoolean hasAudioFocus = new AtomicBoolean(false);
    private final AtomicBoolean isInterrupted = new AtomicBoolean(false);

    // Phone State Monitoring
    private PhoneStateListener phoneStateListener;
    private int lastPhoneState = TelephonyManager.CALL_STATE_IDLE;

    // Broadcast Receivers
    private BroadcastReceiver headphoneReceiver;

    // Current interruption tracking
    private InterruptionType currentInterruption = null;

    public AudioInterruptionManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.telephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);

        Log.d(TAG, "AudioInterruptionManager initialized");
    }

    /**
     * Set the callback for interruption events
     */
    public void setInterruptionCallback(InterruptionCallback callback) {
        this.callback = callback;
    }

    /**
     * Start monitoring for audio interruptions
     */
    public void startMonitoring() {
        Log.d(TAG, "Starting audio interruption monitoring");

        requestAudioFocus();
        setupPhoneStateListener();
        setupBroadcastReceivers();

        Log.d(TAG, "Audio interruption monitoring started");
    }

    /**
     * Stop monitoring for audio interruptions
     */
    public void stopMonitoring() {
        Log.d(TAG, "Stopping audio interruption monitoring");

        abandonAudioFocus();
        removePhoneStateListener();
        removeBroadcastReceivers();

        isInterrupted.set(false);
        currentInterruption = null;

        Log.d(TAG, "Audio interruption monitoring stopped");
    }

    /**
     * Check if currently interrupted
     */
    public boolean isInterrupted() {
        return isInterrupted.get();
    }

    /**
     * Get current interruption type
     */
    public InterruptionType getCurrentInterruption() {
        return currentInterruption;
    }

    /**
     * Request audio focus for recording
     */
    private void requestAudioFocus() {
        if (audioManager == null) {
            Log.w(TAG, "AudioManager is null, cannot request audio focus");
            return;
        }

        int result;

      // Use AudioFocusRequest for API 26+
      AudioAttributes audioAttributes = new AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
              .build();

      audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
              .setAudioAttributes(audioAttributes)
              .setAcceptsDelayedFocusGain(false) // Don't accept delayed focus for recording
              .setOnAudioFocusChangeListener(audioFocusChangeListener)
              .build();

      result = audioManager.requestAudioFocus(audioFocusRequest);

      if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            hasAudioFocus.set(true);
            Log.d(TAG, "Audio focus granted");
        } else {
            hasAudioFocus.set(false);
            Log.w(TAG, "Audio focus request failed: " + result);
        }
    }

    /**
     * Abandon audio focus
     */
    private void abandonAudioFocus() {
        if (audioManager == null) return;

        if (audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
            audioFocusRequest = null;
        } else {
            audioManager.abandonAudioFocus(audioFocusChangeListener);
        }

        hasAudioFocus.set(false);
        Log.d(TAG, "Audio focus abandoned");
    }

    /**
     * Audio focus change listener - handles interruptions from other audio apps
     */
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            Log.d(TAG, "Audio focus changed: " + audioFocusChangeToString(focusChange));

            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    // Permanent loss - stop recording
                    hasAudioFocus.set(false);
                    handleInterruptionBegan(InterruptionType.AUDIO_FOCUS_LOSS);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    // Temporary loss - pause recording
                    hasAudioFocus.set(false);
                    handleInterruptionBegan(InterruptionType.AUDIO_FOCUS_LOSS_TRANSIENT);
                    break;

                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    // For recording, we can continue at lower volume - don't lose focus entirely
                    // This handles camera scanning, browsing, other app usage scenarios
                    Log.d(TAG, "Temporary audio focus loss (can duck) - continuing recording at lower priority");
                    handleInterruptionBegan(InterruptionType.AUDIO_FOCUS_LOSS_TRANSIENT_CAN_DUCK);
                    break;

                case AudioManager.AUDIOFOCUS_GAIN:
                    // Regained focus - resume if appropriate
                    hasAudioFocus.set(true);
                    if (isInterrupted.get()) {
                        handleInterruptionEnded(currentInterruption);
                    }
                    break;

                default:
                    Log.d(TAG, "Unhandled audio focus change: " + focusChange);
                    break;
            }
        }
    };

    /**
     * Setup phone state listener for call interruptions
     */
    private void setupPhoneStateListener() {
        if (telephonyManager == null) {
            Log.w(TAG, "TelephonyManager is null, cannot monitor phone state");
            return;
        }

        phoneStateListener = new PhoneStateListener() {
            @Override
            public void onCallStateChanged(int state, String phoneNumber) {
                Log.d(TAG, "Phone state changed: " + phoneStateToString(state));

                switch (state) {
                    case TelephonyManager.CALL_STATE_RINGING:
                    case TelephonyManager.CALL_STATE_OFFHOOK:
                        // Incoming or outgoing call - pause recording
                        if (lastPhoneState == TelephonyManager.CALL_STATE_IDLE) {
                            handleInterruptionBegan(InterruptionType.PHONE_CALL);
                        }
                        break;

                    case TelephonyManager.CALL_STATE_IDLE:
                        // Call ended - resume recording if we were interrupted by a call
                        if (lastPhoneState != TelephonyManager.CALL_STATE_IDLE &&
                            currentInterruption == InterruptionType.PHONE_CALL) {
                            handleInterruptionEnded(InterruptionType.PHONE_CALL);
                        }
                        break;
                }

                lastPhoneState = state;
            }
        };

        try {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d(TAG, "Phone state listener registered");
        } catch (SecurityException e) {
            Log.w(TAG, "No permission to listen to phone state", e);
        }
    }

    /**
     * Remove phone state listener
     */
    private void removePhoneStateListener() {
        if (telephonyManager != null && phoneStateListener != null) {
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                Log.d(TAG, "Phone state listener unregistered");
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering phone state listener", e);
            }
            phoneStateListener = null;
        }
    }

    /**
     * Setup broadcast receivers for headphone and notification events
     */
    private void setupBroadcastReceivers() {
        // Headphone connect/disconnect receiver
        headphoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                Log.d(TAG, "Headphone broadcast received: " + action);

                if (AudioManager.ACTION_HEADSET_PLUG.equals(action)) {
                    int state = intent.getIntExtra("state", -1);
                    String name = intent.getStringExtra("name");

                    if (state == 0) {
                        // Headphones disconnected
                        Log.d(TAG, "Headphones disconnected: " + name);
                        if (callback != null) {
                            callback.onAudioRouteChanged("Headphones disconnected");
                        }
                    } else if (state == 1) {
                        // Headphones connected
                        Log.d(TAG, "Headphones connected: " + name);
                        if (callback != null) {
                            callback.onAudioRouteChanged("Headphones connected");
                        }
                    }
                }
            }
        };

        // Register headphone receiver
        IntentFilter headphoneFilter = new IntentFilter(AudioManager.ACTION_HEADSET_PLUG);
        context.registerReceiver(headphoneReceiver, headphoneFilter);

        Log.d(TAG, "Broadcast receivers registered");
    }

    /**
     * Remove broadcast receivers
     */
    private void removeBroadcastReceivers() {
        try {
            if (headphoneReceiver != null) {
                context.unregisterReceiver(headphoneReceiver);
                headphoneReceiver = null;
            }
            Log.d(TAG, "Broadcast receivers unregistered");
        } catch (Exception e) {
            Log.w(TAG, "Error unregistering broadcast receivers", e);
        }
    }

    /**
     * Handle interruption began
     */
    private void handleInterruptionBegan(InterruptionType type) {
        if (isInterrupted.get() && currentInterruption == type) {
            // Already interrupted by the same type
            return;
        }

        Log.d(TAG, "Audio interruption began: " + type);
        isInterrupted.set(true);
        currentInterruption = type;

        if (callback != null) {
            callback.onInterruptionBegan(type);
        }
    }

    /**
     * Handle interruption ended
     */
    private void handleInterruptionEnded(InterruptionType type) {
        if (!isInterrupted.get() || currentInterruption != type) {
            // Not interrupted by this type
            return;
        }

        Log.d(TAG, "Audio interruption ended: " + type + ", should resume: " + true);
        isInterrupted.set(false);
        currentInterruption = null;

        if (callback != null) {
            callback.onInterruptionEnded(type, true);
        }
    }

    /**
     * Helper method to convert audio focus change to string
     */
    private String audioFocusChangeToString(int focusChange) {
      return switch (focusChange) {
        case AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN";
        case AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS";
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT";
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
          "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK";
        default -> "UNKNOWN (" + focusChange + ")";
      };
    }

    /**
     * Helper method to convert phone state to string
     */
    private String phoneStateToString(int state) {
      return switch (state) {
        case TelephonyManager.CALL_STATE_IDLE -> "CALL_STATE_IDLE";
        case TelephonyManager.CALL_STATE_RINGING -> "CALL_STATE_RINGING";
        case TelephonyManager.CALL_STATE_OFFHOOK -> "CALL_STATE_OFFHOOK";
        default -> "UNKNOWN (" + state + ")";
      };
    }
}
