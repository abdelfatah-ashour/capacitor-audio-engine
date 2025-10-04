package com.capacitor.audioengine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PlaybackManager implements MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "PlaybackManager";

    // Simple constants
    private static final long PROGRESS_UPDATE_INTERVAL_MS = 250;
    private static final int AUDIO_CONTENT_TYPE = AudioAttributes.CONTENT_TYPE_MUSIC;
    private static final int AUDIO_USAGE = AudioAttributes.USAGE_MEDIA;
    private static final long SEEK_THROTTLE_MS = 100; // Minimum time between seeks
    private static final double SEEK_THRESHOLD_SECONDS = 0.5; // Ignore seeks smaller than this when playing

    public interface PlaybackManagerListener {
        void onPlaybackStarted(String trackId);
        void onPlaybackPaused(String trackId);
        void onPlaybackStopped(String trackId);
        void onPlaybackError(String trackId, String error);
        void onPlaybackProgress(String trackId, long currentPosition, long duration);
    }

    private final Context context;
    private final PlaybackManagerListener listener;
    private final Handler mainHandler;

    // Essential state only
    private final Map<String, MediaPlayer> audioPlayers = new HashMap<>();
    private final Map<String, Boolean> preparedPlayers = new HashMap<>();
    private final Map<String, String> urlToTrackIdMap = new HashMap<>();
    private final Map<String, String> trackIdToUrlMap = new HashMap<>();
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;

    private String currentTrackId = null;
    private int trackCounter = 0;
    private boolean isTrackingProgress = false;

    // Minimal seek state to prevent UI progress bar interference
    private long lastSeekTime = 0;
    private double lastSeekPosition = 0;

    private final Runnable progressUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTrackingProgress && currentTrackId != null) {
                updateProgress();
                mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS);
            }
        }
    };

    public PlaybackManager(Context context, PlaybackManagerListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        initializeAudioEngine();
    }

    private void initializeAudioEngine() {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AUDIO_USAGE)
                .setContentType(AUDIO_CONTENT_TYPE)
                .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build();

        Log.d(TAG, "Audio engine initialized");
    }

    private String generateTrackId() {
        return "track_" + (trackCounter++);
    }

    private String resolveTrackId(String identifier) {
        if (identifier == null) {
            return currentTrackId;
        }
        if (identifier.startsWith("track_")) {
            return identifier;
        }
        return urlToTrackIdMap.get(identifier);
    }

    public boolean preloadTrack(String trackId, String url) {
        Log.d(TAG, "Preloading track " + trackId + " with URL: " + url);

        try {
            MediaPlayer player = createMediaPlayer();
            setDataSource(player, url);
            player.prepareAsync();

            audioPlayers.put(trackId, player);
            urlToTrackIdMap.put(url, trackId);
            trackIdToUrlMap.put(trackId, url);

            Log.d(TAG, "Track preloaded: " + trackId);
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Failed to preload track: " + trackId, e);
            cleanupTrack(trackId, url);
            return false;
        }
    }

    private void cleanupTrack(String trackId, String url) {
        MediaPlayer player = audioPlayers.remove(trackId);
        if (player != null) {
            try {
                player.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player", e);
            }
        }
        urlToTrackIdMap.remove(url);
        trackIdToUrlMap.remove(trackId);
        preparedPlayers.remove(trackId);
    }

    private MediaPlayer createMediaPlayer() {
        MediaPlayer player = new MediaPlayer();
        player.setOnCompletionListener(this);
        player.setOnPreparedListener(this);
        player.setOnErrorListener(this);

        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AUDIO_USAGE)
                .setContentType(AUDIO_CONTENT_TYPE)
                .build());

        player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
        return player;
    }

    private void setDataSource(MediaPlayer player, String url) throws IOException {
        if (url.startsWith("http://") || url.startsWith("https://")) {
            player.setDataSource(url);
        } else if (url.startsWith("file://")) {
            player.setDataSource(url.substring(7));
        } else if (url.startsWith("content://")) {
            player.setDataSource(context, android.net.Uri.parse(url));
        } else {
            File file = new File(url);
            if (file.exists()) {
                player.setDataSource(url);
            } else {
                throw new IOException("File not found: " + url);
            }
        }
    }

    public void preloadTracks(List<String> trackUrls) throws Exception {
        if (trackUrls == null || trackUrls.isEmpty()) {
            throw new Exception("Track URLs list cannot be empty");
        }

        Log.d(TAG, "Preloading " + trackUrls.size() + " tracks");

        for (String url : trackUrls) {
            if (urlToTrackIdMap.containsKey(url)) {
                Log.d(TAG, "Track already preloaded: " + url);
                continue;
            }

            String trackId = generateTrackId();
            if (!preloadTrack(trackId, url)) {
                throw new Exception("Failed to preload track: " + url);
            }
        }

        Log.d(TAG, "All tracks preloaded (" + audioPlayers.size() + " total)");
    }

    /**
     * Wait for all tracks to be prepared and return their metadata
     * @param trackUrls List of track URLs
     * @return Map of URL to metadata (duration in milliseconds)
     */
    public Map<String, Map<String, Object>> waitForTracksMetadata(List<String> trackUrls) {
        Map<String, Map<String, Object>> metadataMap = new HashMap<>();
        long overallStartTime = System.currentTimeMillis();

        Log.d(TAG, "Waiting for " + trackUrls.size() + " tracks to be prepared (no timeout)");

        for (String url : trackUrls) {
            String trackId = urlToTrackIdMap.get(url);
            if (trackId == null) {
                Log.w(TAG, "Track not found in mapping: " + url);
                continue;
            }

            // Wait indefinitely for track to be prepared
            long trackStartTime = System.currentTimeMillis();
            waitForTrackPrepared(trackId);

            Map<String, Object> metadata = new HashMap<>();
            MediaPlayer player = audioPlayers.get(trackId);
            if (player != null) {
                try {
                    int duration = player.getDuration();
                    metadata.put("duration", duration > 0 ? duration : 0);
                    metadata.put("prepared", true);
                    long waitTime = System.currentTimeMillis() - trackStartTime;
                    Log.d(TAG, "Track prepared after " + waitTime + "ms: " + url + " (duration: " + duration + "ms)");
                } catch (Exception e) {
                    Log.e(TAG, "Error getting duration for track: " + url, e);
                    metadata.put("duration", 0);
                    metadata.put("prepared", false);
                }
            } else {
                Log.w(TAG, "Player not found for track: " + url);
                metadata.put("duration", 0);
                metadata.put("prepared", false);
            }

            metadataMap.put(url, metadata);
        }

        long totalWaitTime = System.currentTimeMillis() - overallStartTime;
        Log.d(TAG, "All tracks processed in " + totalWaitTime + "ms");

        return metadataMap;
    }

    /**
     * Wait for a specific track to be prepared (no timeout)
     * @param trackId Track ID to wait for
     */
    private void waitForTrackPrepared(String trackId) {
        while (true) {
            Boolean prepared = preparedPlayers.get(trackId);
            if (prepared != null && prepared) {
                return;
            }

            try {
                Thread.sleep(50); // Check every 50ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for track: " + trackId);
                return;
            }
        }
    }
    // Playback Control Methods

    public void play(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) {
            Log.e(TAG, "Track not found: " + identifier);
            if (listener != null) {
                listener.onPlaybackError(identifier != null ? identifier : "null", "Track not found");
            }
            return;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> play(identifier));
            return;
        }

        MediaPlayer player = audioPlayers.get(trackId);
        if (player == null) {
            Log.e(TAG, "Track not preloaded: " + trackId);
            if (listener != null) {
                listener.onPlaybackError(trackId, "Track not preloaded");
            }
            return;
        }

        // Stop current track if different
        if (currentTrackId != null && !currentTrackId.equals(trackId)) {
            stop(currentTrackId);
        }

        Boolean isPrepared = preparedPlayers.get(trackId);
        if (isPrepared == null || !isPrepared) {
            Log.w(TAG, "Player not prepared yet: " + trackId);
            if (listener != null) {
                listener.onPlaybackError(trackId, "Track not prepared yet");
            }
            return;
        }

        if (requestAudioFocus()) {
            try {
                if (!player.isPlaying()) {
                    player.start();
                    currentTrackId = trackId;
                    startProgressTracking();

                    if (listener != null) {
                        listener.onPlaybackStarted(trackId);
                    }
                    Log.d(TAG, "Started playback: " + trackId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start playback: " + trackId, e);
                if (listener != null) {
                    listener.onPlaybackError(trackId, e.getMessage());
                }
            }
        }
    }

    public void pause(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> pause(identifier));
            return;
        }

        MediaPlayer player = audioPlayers.get(trackId);
        if (player != null && player.isPlaying()) {
            try {
                player.pause();
                if (listener != null) {
                    listener.onPlaybackPaused(trackId);
                }
                Log.d(TAG, "Paused playback: " + trackId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to pause: " + trackId, e);
            }
        }
    }

    public void stop(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> stop(identifier));
            return;
        }

        MediaPlayer player = audioPlayers.get(trackId);
        if (player != null) {
            try {
                player.stop();
                if (currentTrackId != null && currentTrackId.equals(trackId)) {
                    currentTrackId = null;
                    stopProgressTracking();
                    abandonAudioFocus();
                }

                Log.d(TAG, "Stopped playback: " + trackId);

                if (listener != null) {
                    listener.onPlaybackStopped(trackId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop: " + trackId, e);
            }
        }
    }

    public void seekTo(String identifier, double seconds) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> seekTo(identifier, seconds));
            return;
        }

        MediaPlayer player = audioPlayers.get(trackId);
        if (player != null) {
            try {
                // Prevent excessive seeking from UI progress bar updates
                long currentTime = System.currentTimeMillis();

                // If playing, ignore tiny position changes (likely from UI sync, not user interaction)
                if (player.isPlaying()) {
                    double currentPos = player.getCurrentPosition() / 1000.0;
                    double seekDiff = Math.abs(seconds - currentPos);

                    // Ignore if this is just the UI syncing with current position
                    if (seekDiff < SEEK_THRESHOLD_SECONDS) {
                        return;
                    }
                }

                // Throttle rapid seeks
                if (currentTime - lastSeekTime < SEEK_THROTTLE_MS) {
                    return;
                }

                player.seekTo((int) (seconds * 1000));
                lastSeekTime = currentTime;
                lastSeekPosition = seconds;

                Log.d(TAG, "Seeked to " + seconds + "s: " + trackId);
            } catch (Exception e) {
                Log.e(TAG, "Failed to seek: " + trackId, e);
            }
        }
    }

    public boolean isPlaying(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return false;

        MediaPlayer player = audioPlayers.get(trackId);
        return player != null && player.isPlaying();
    }

    public double getCurrentPosition(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return 0.0;

        MediaPlayer player = audioPlayers.get(trackId);
        return player != null ? player.getCurrentPosition() / 1000.0 : 0.0;
    }

    public double getDuration(String identifier) {
        String trackId = resolveTrackId(identifier);
        if (trackId == null) return 0.0;

        MediaPlayer player = audioPlayers.get(trackId);
        if (player != null) {
            int duration = player.getDuration();
            return duration > 0 ? duration / 1000.0 : 0.0;
        }
        return 0.0;
    }

    public String getCurrentTrackId() {
        return currentTrackId;
    }

    public String getCurrentTrackUrl() {
        return currentTrackId != null ? trackIdToUrlMap.get(currentTrackId) : null;
    }

    // Progress tracking

    private void startProgressTracking() {
        if (!isTrackingProgress) {
            isTrackingProgress = true;
            mainHandler.post(progressUpdateRunnable);
        }
    }

    private void stopProgressTracking() {
        if (isTrackingProgress) {
            isTrackingProgress = false;
            mainHandler.removeCallbacks(progressUpdateRunnable);
        }
    }

    private void updateProgress() {
        if (currentTrackId != null && listener != null) {
            MediaPlayer player = audioPlayers.get(currentTrackId);
            if (player != null) {
                long currentPosition = player.getCurrentPosition();
                long duration = player.getDuration();
                listener.onPlaybackProgress(currentTrackId, currentPosition, duration);
            }
        }
    }

    // MediaPlayer Listeners

    @Override
    public void onCompletion(MediaPlayer mp) {
        String completedTrackId = findTrackIdByPlayer(mp);
        if (completedTrackId != null) {
            if (listener != null) {
                listener.onPlaybackStopped(completedTrackId);
            }

            if (currentTrackId != null && currentTrackId.equals(completedTrackId)) {
                currentTrackId = null;
                stopProgressTracking();
            }

            Log.d(TAG, "Track completed: " + completedTrackId);
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        String preparedTrackId = findTrackIdByPlayer(mp);
        if (preparedTrackId != null) {
            preparedPlayers.put(preparedTrackId, true);
            Log.d(TAG, "Track prepared: " + preparedTrackId + " (Duration: " + mp.getDuration() + "ms)");
        }
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        String errorTrackId = findTrackIdByPlayer(mp);
        if (errorTrackId != null) {
            String errorMessage = "MediaPlayer error: " + what + ", " + extra;
            Log.e(TAG, "Error for track " + errorTrackId + ": " + errorMessage);

            String trackUrl = trackIdToUrlMap.get(errorTrackId);

            // Try to recreate once
            try {
                mp.reset();
                mp.release();

                if (trackUrl != null && preloadTrack(errorTrackId, trackUrl)) {
                    Log.d(TAG, "Successfully recreated track: " + errorTrackId);
                } else {
                    cleanupTrack(errorTrackId, trackUrl);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to recreate track: " + errorTrackId, e);
                cleanupTrack(errorTrackId, trackUrl);
            }

            if (listener != null) {
                listener.onPlaybackError(errorTrackId, errorMessage);
            }

            if (currentTrackId != null && currentTrackId.equals(errorTrackId)) {
                currentTrackId = null;
                stopProgressTracking();
            }
        }
        return true;
    }

    private String findTrackIdByPlayer(MediaPlayer player) {
        for (Map.Entry<String, MediaPlayer> entry : audioPlayers.entrySet()) {
            if (entry.getValue() == player) {
                return entry.getKey();
            }
        }
        return null;
    }

    // Audio Focus

    private boolean requestAudioFocus() {
        int result = audioManager.requestAudioFocus(audioFocusRequest);
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                if (currentTrackId != null) {
                    MediaPlayer player = audioPlayers.get(currentTrackId);
                    if (player != null && !player.isPlaying()) {
                        player.start();
                    }
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (currentTrackId != null) {
                    pause(currentTrackId);
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Just duck the volume
                if (currentTrackId != null) {
                    MediaPlayer player = audioPlayers.get(currentTrackId);
                    if (player != null && player.isPlaying()) {
                        player.setVolume(0.3f, 0.3f);
                    }
                }
                break;
        }
    }

    // Cleanup

    public void release() {
        if (currentTrackId != null) {
            stop(currentTrackId);
        }

        for (Map.Entry<String, MediaPlayer> entry : audioPlayers.entrySet()) {
            try {
                MediaPlayer player = entry.getValue();
                if (player != null) {
                    if (player.isPlaying()) {
                        player.stop();
                    }
                    player.release();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing player: " + entry.getKey(), e);
            }
        }

        audioPlayers.clear();
        preparedPlayers.clear();
        urlToTrackIdMap.clear();
        trackIdToUrlMap.clear();

        abandonAudioFocus();
        Log.d(TAG, "PlaybackManager released");
    }
}
