package com.capacitor.audioengine;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages audio playback operations with proper state management and resource cleanup
 */
public class AudioPlaybackManager {
    private static final String TAG = "AudioPlaybackManager";

    private final Context context;
    private final PlaybackStateListener stateListener;

    private MediaPlayer mediaPlayer;
    private final AtomicBoolean isPlaying = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private final AtomicReference<String> currentUri = new AtomicReference<>();

    private float playbackSpeed = 1.0f;
    private float playbackVolume = 1.0f;
    private boolean isLooping = false;

    public interface PlaybackStateListener {
        void onPlaybackStarted(String uri);
        void onPlaybackPaused();
        void onPlaybackResumed();
        void onPlaybackStopped();
        void onPlaybackCompleted();
        void onPlaybackError(AudioEngineError error, String details);
        void onPlaybackProgress(double currentTime, double duration);
    }

    public AudioPlaybackManager(Context context, PlaybackStateListener listener) {
        this.context = context;
        this.stateListener = listener;
    }

    /**
     * Start playback of audio from URI
     */
    public synchronized void startPlayback(String uri, AudioPlaybackConfig config) throws AudioPlaybackException {
        try {
            // Validate URI
            ValidationUtils.validateUri(uri);

            if (ValidationUtils.isRemoteUrl(uri)) {
                ValidationUtils.validateRemoteUrl(uri);
            } else {
                ValidationUtils.validateFileExists(uri);
            }

            // Validate playback parameters
            ValidationUtils.validatePlaybackParameters(config.getSpeed(), config.getVolume());

            // Stop any current playback
            if (mediaPlayer != null) {
                stopPlayback();
            }

            // Create new MediaPlayer
            mediaPlayer = new MediaPlayer();

            // Configure MediaPlayer
            setupMediaPlayer(uri, config);

            // Prepare and start playback
            mediaPlayer.prepareAsync();

        } catch (SecurityException e) {
            cleanup();
            throw new AudioPlaybackException(AudioEngineError.INVALID_FILE_PATH, e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            cleanup();
            throw new AudioPlaybackException(AudioEngineError.INVALID_PARAMETERS, e.getMessage(), e);
        } catch (Exception e) {
            cleanup();
            throw new AudioPlaybackException(AudioEngineError.INITIALIZATION_FAILED, e.getMessage(), e);
        }
    }

    /**
     * Pause playback
     */
    public synchronized void pausePlayback() throws AudioPlaybackException {
        if (mediaPlayer == null || !isPlaying.get()) {
            throw new AudioPlaybackException(AudioEngineError.NO_ACTIVE_PLAYBACK);
        }

        if (isPaused.get()) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_STATE, "Playback is already paused");
        }

        try {
            mediaPlayer.pause();
            isPlaying.set(false);
            isPaused.set(true);

            Log.d(TAG, "Playback paused");
            stateListener.onPlaybackPaused();

        } catch (Exception e) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_STATE, e.getMessage(), e);
        }
    }

    /**
     * Resume playback
     */
    public synchronized void resumePlayback() throws AudioPlaybackException {
        if (mediaPlayer == null) {
            throw new AudioPlaybackException(AudioEngineError.NO_ACTIVE_PLAYBACK);
        }

        if (isPlaying.get()) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_STATE, "Playback is already active");
        }

        try {
            mediaPlayer.start();
            isPlaying.set(true);
            isPaused.set(false);

            Log.d(TAG, "Playback resumed");
            stateListener.onPlaybackResumed();

        } catch (Exception e) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_STATE, e.getMessage(), e);
        }
    }

    /**
     * Stop playback
     */
    public synchronized void stopPlayback() throws AudioPlaybackException {
        if (mediaPlayer == null) {
            throw new AudioPlaybackException(AudioEngineError.NO_ACTIVE_PLAYBACK);
        }

        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            cleanup();

            Log.d(TAG, "Playback stopped");
            stateListener.onPlaybackStopped();

        } catch (Exception e) {
            cleanup();
            throw new AudioPlaybackException(AudioEngineError.UNKNOWN_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Seek to specific time position
     */
    public synchronized void seekTo(int timeMs) throws AudioPlaybackException {
        if (mediaPlayer == null) {
            throw new AudioPlaybackException(AudioEngineError.NO_ACTIVE_PLAYBACK);
        }

        if (timeMs < 0) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_PARAMETERS, "Seek time cannot be negative");
        }

        try {
            mediaPlayer.seekTo(timeMs);
            Log.d(TAG, "Seeked to position: " + timeMs + "ms");

        } catch (Exception e) {
            throw new AudioPlaybackException(AudioEngineError.INVALID_STATE, e.getMessage(), e);
        }
    }

    /**
     * Set playback speed (API 23+)
     */
    public synchronized void setPlaybackSpeed(float speed) throws AudioPlaybackException {
        ValidationUtils.validatePlaybackParameters(speed, null);

        if (mediaPlayer != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            try {
                mediaPlayer.setPlaybackParams(mediaPlayer.getPlaybackParams().setSpeed(speed));
                this.playbackSpeed = speed;
                Log.d(TAG, "Playback speed set to: " + speed);
            } catch (Exception e) {
                throw new AudioPlaybackException(AudioEngineError.INVALID_STATE,
                    "Failed to set playback speed: " + e.getMessage(), e);
            }
        } else {
            this.playbackSpeed = speed; // Store for future use
        }
    }

    /**
     * Set playback volume
     */
    public synchronized void setVolume(float volume) throws AudioPlaybackException {
        ValidationUtils.validatePlaybackParameters(null, volume);

        if (mediaPlayer != null) {
            try {
                mediaPlayer.setVolume(volume, volume);
                this.playbackVolume = volume;
                Log.d(TAG, "Volume set to: " + volume);
            } catch (Exception e) {
                throw new AudioPlaybackException(AudioEngineError.INVALID_STATE,
                    "Failed to set volume: " + e.getMessage(), e);
            }
        } else {
            this.playbackVolume = volume; // Store for future use
        }
    }

    /**
     * Set looping mode
     */
    public synchronized void setLooping(boolean looping) {
        this.isLooping = looping;
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(looping);
            Log.d(TAG, "Looping set to: " + looping);
        }
    }

    /**
     * Get current playback position in milliseconds
     */
    public int getCurrentPosition() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getCurrentPosition();
            } catch (Exception e) {
                Log.w(TAG, "Error getting current position", e);
            }
        }
        return 0;
    }

    /**
     * Get total duration in milliseconds
     */
    public int getDuration() {
        if (mediaPlayer != null) {
            try {
                return mediaPlayer.getDuration();
            } catch (Exception e) {
                Log.w(TAG, "Error getting duration", e);
            }
        }
        return 0;
    }

    /**
     * Check if currently playing
     */
    public boolean isPlaying() {
        return isPlaying.get();
    }

    /**
     * Check if playback is paused
     */
    public boolean isPaused() {
        return isPaused.get();
    }

    /**
     * Get current URI being played
     */
    public String getCurrentUri() {
        return currentUri.get();
    }

    /**
     * Get current playback speed
     */
    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    /**
     * Get current volume
     */
    public float getVolume() {
        return playbackVolume;
    }

    /**
     * Check if looping is enabled
     */
    public boolean isLooping() {
        return isLooping;
    }

    /**
     * Clean up resources
     */
    public synchronized void cleanup() {
        if (mediaPlayer != null) {
            ResourceManager.releaseMediaPlayer(mediaPlayer);
            mediaPlayer = null;
        }

        isPlaying.set(false);
        isPaused.set(false);
        currentUri.set(null);
    }

    private void setupMediaPlayer(String uri, AudioPlaybackConfig config) throws Exception {
        // Set data source
        if (ValidationUtils.isRemoteUrl(uri)) {
            mediaPlayer.setDataSource(uri);
        } else {
            String filePath = uri.startsWith("file://") ? uri.substring(7) : uri;
            mediaPlayer.setDataSource(filePath);
        }

        // Store current URI
        currentUri.set(uri);

        // Configure playback parameters
        this.playbackSpeed = config.getSpeed();
        this.playbackVolume = config.getVolume();
        this.isLooping = config.isLoop();

        mediaPlayer.setVolume(playbackVolume, playbackVolume);
        mediaPlayer.setLooping(isLooping);

        // Set listeners
        mediaPlayer.setOnPreparedListener(mp -> {
            try {
                // Set playback speed if supported
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && playbackSpeed != 1.0f) {
                    mp.setPlaybackParams(mp.getPlaybackParams().setSpeed(playbackSpeed));
                }

                // Seek to start time if specified
                if (config.getStartTime() > 0) {
                    mp.seekTo(config.getStartTime() * 1000);
                }

                // Start playback
                mp.start();
                isPlaying.set(true);
                isPaused.set(false);

                Log.d(TAG, "Playback started: " + uri);
                stateListener.onPlaybackStarted(uri);

            } catch (Exception e) {
                Log.e(TAG, "Error in onPrepared callback", e);
                stateListener.onPlaybackError(AudioEngineError.INITIALIZATION_FAILED, e.getMessage());
            }
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            isPlaying.set(false);
            isPaused.set(false);

            Log.d(TAG, "Playback completed");
            stateListener.onPlaybackCompleted();
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            isPlaying.set(false);
            isPaused.set(false);

            String errorMessage = getDetailedErrorMessage(what, extra, uri);
            Log.e(TAG, "MediaPlayer error: " + errorMessage);
            stateListener.onPlaybackError(AudioEngineError.CODEC_ERROR, errorMessage);
            return true;
        });
    }

    private String getDetailedErrorMessage(int what, int extra, String uri) {
        String baseMessage = "MediaPlayer error: ";

        switch (what) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                baseMessage += "Unknown error";
                break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                baseMessage += "Media server died";
                break;
            default:
                baseMessage += "Error code " + what;
        }

        switch (extra) {
            case MediaPlayer.MEDIA_ERROR_IO:
                baseMessage += " (I/O error)";
                break;
            case MediaPlayer.MEDIA_ERROR_MALFORMED:
                baseMessage += " (Malformed media)";
                break;
            case MediaPlayer.MEDIA_ERROR_UNSUPPORTED:
                baseMessage += " (Unsupported format)";
                break;
            case MediaPlayer.MEDIA_ERROR_TIMED_OUT:
                baseMessage += " (Timeout)";
                break;
            default:
                if (extra != 0) {
                    baseMessage += " (Extra: " + extra + ")";
                }
        }

        if (ValidationUtils.isRemoteUrl(uri)) {
            baseMessage += " - Check network connection and URL accessibility";
        }

        return baseMessage;
    }

    /**
     * Custom exception for playback operations
     */
    public static class AudioPlaybackException extends Exception {
        private final AudioEngineError errorCode;

        public AudioPlaybackException(AudioEngineError errorCode) {
            super(errorCode.getMessage());
            this.errorCode = errorCode;
        }

        public AudioPlaybackException(AudioEngineError errorCode, String details) {
            super(errorCode.getDetailedMessage(details));
            this.errorCode = errorCode;
        }

        public AudioPlaybackException(AudioEngineError errorCode, String details, Throwable cause) {
            super(errorCode.getDetailedMessage(details), cause);
            this.errorCode = errorCode;
        }

        public AudioEngineError getErrorCode() {
            return errorCode;
        }
    }
}
