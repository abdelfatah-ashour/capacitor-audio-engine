package com.capacitor.audioengine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Manages audio playback for multiple tracks with MediaPlayer
 * Follows clean architecture and separation of concerns
 */
class PlaybackManager implements AudioManager.OnAudioFocusChangeListener {

    /**
     * Callback interface for playback events
     */
    interface PlaybackCallback {
        void onStatusChanged(String status, String url, int position);
        void onError(String trackId, String message);
        void onProgress(String trackId, String url, int currentPosition, int duration, boolean isPlaying);
        void onTrackCompleted(String trackId, String url);
    }

    private static final String TAG = "PlaybackManager";
    private static final int PROGRESS_UPDATE_INTERVAL_MS = 1000; // 1 second

    private final Context context;
    private final PlaybackCallback callback;
    private final Handler mainHandler;
    private final Map<String, TrackInfo> preloadedTracks;

    // Audio focus management
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus = false;

    // Current playback state
    private String currentTrackUrl = null;
    private PlaybackStatus currentStatus = PlaybackStatus.IDLE;

    // Progress monitoring
    private Timer progressTimer;
    private volatile boolean isProgressMonitoring = false;

    /**
     * Internal class to hold track information
     */
    private static class TrackInfo {
        MediaPlayer player;
        String url;
        String trackId;
        boolean isLoaded;
        String mimeType;
        int duration;
        long size;

        TrackInfo(String url, String trackId) {
            this.url = url;
            this.trackId = trackId;
            this.isLoaded = false;
            this.mimeType = "";
            this.duration = 0;
            this.size = 0;
        }
    }

    PlaybackManager(Context context, PlaybackCallback callback) {
        this.context = context;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.preloadedTracks = new HashMap<>();

        // Initialize audio manager
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Create audio focus request for playback
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
            )
            .setOnAudioFocusChangeListener(this)
            .build();
    }

    /**
     * Preload a track from URL (supports HTTP/HTTPS URLs and local file URIs)
     */
    void preloadTrack(String url, PreloadCallback preloadCallback) {
        if (url == null || url.isEmpty()) {
            preloadCallback.onError(url, "Invalid URL");
            return;
        }

        String trackId = generateTrackId(url);

        // Check if already preloaded
        if (preloadedTracks.containsKey(url)) {
            TrackInfo existing = preloadedTracks.get(url);
            if (existing != null && existing.isLoaded) {
                preloadCallback.onSuccess(existing.url, existing.mimeType, existing.duration, existing.size);
                return;
            }
        }

        TrackInfo trackInfo = new TrackInfo(url, trackId);

        // Normalize the URL/URI to handle different formats first (needed for listeners)
        String normalizedUrl = normalizeAudioUrl(url);
        Log.d(TAG, "Normalized URL: " + normalizedUrl + " (original: " + url + ")");

        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(
                new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            );

            player.setOnPreparedListener(mp -> {
                trackInfo.isLoaded = true;
                // Convert duration from milliseconds to seconds
                trackInfo.duration = mp.getDuration() / 1000;

                // Detect MIME type from file extension or URL
                trackInfo.mimeType = detectMimeType(url, normalizedUrl);

                // Get file size for local files
                trackInfo.size = getFileSize(normalizedUrl);

                Log.d(TAG, "Track preloaded successfully: " + url + " (mimeType: " + trackInfo.mimeType + ", duration: " + trackInfo.duration + "s, size: " + trackInfo.size + " bytes)");
                preloadCallback.onSuccess(trackInfo.url, trackInfo.mimeType, trackInfo.duration, trackInfo.size);
            });

            player.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "Error preloading track: " + url + ", what=" + what + ", extra=" + extra);
                preloadedTracks.remove(url);
                preloadCallback.onError(url, "Error loading track: " + what);
                return true;
            });

            player.setOnCompletionListener(mp -> {
                // Only emit events if this track is actually the current playing track
                if (currentTrackUrl != null && currentTrackUrl.equals(url)) {
                    Log.d(TAG, "Track completed: " + url);

                    // Stop the track completely (reset to beginning)
                    mp.seekTo(0);

                    // Clear current track state
                    currentTrackUrl = null;

                    // Update status to idle and stop monitoring
                    updateStatus(PlaybackStatus.IDLE);
                    stopProgressMonitoring();

                    // Abandon audio focus
                    abandonAudioFocus();

                    // Emit completion event
                    if (callback != null) {
                        callback.onTrackCompleted(trackInfo.trackId, url);
                    }

                    Log.d(TAG, "Track completed and automatically stopped: " + url);
                }
            });

            // Set data source based on URL type
            if (isRemoteUrl(url)) {
                // For HTTP/HTTPS URLs, use Uri parsing
                Uri uri = Uri.parse(normalizedUrl);
                player.setDataSource(context, uri);
                Log.d(TAG, "Using remote URL with Uri: " + uri);
            } else {
                // For all local files (file://, capacitor://, or direct paths), use file path
                // The normalization already converts file:// to path
                File file = new File(normalizedUrl);
                if (!file.exists()) {
                    Log.e(TAG, "File does not exist: " + normalizedUrl);
                    preloadCallback.onError(url, "File does not exist: " + normalizedUrl);
                    return;
                }
                player.setDataSource(normalizedUrl);
                Log.d(TAG, "Using local file path: " + normalizedUrl);
            }
            player.prepareAsync();

            trackInfo.player = player;
            preloadedTracks.put(url, trackInfo);

        } catch (IOException e) {
            Log.e(TAG, "IOException preloading track: " + url, e);
            preloadCallback.onError(url, "IOException: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Exception preloading track: " + url, e);
            preloadCallback.onError(url, "Exception: " + e.getMessage());
        }
    }

    /**
     * Normalize audio URL/URI to handle different formats:
     * - HTTP/HTTPS URLs (CDN) - returned as-is
     * - Capacitor file URIs (capacitor://localhost/_capacitor_file_) - converted to file path
     * - file:// URIs - converted to file path
     * - Direct file paths - returned as-is
     */
    private String normalizeAudioUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        // Handle HTTP/HTTPS URLs - return as-is
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        // Handle Capacitor file URI format
        if (url.contains("capacitor://localhost/_capacitor_file_")) {
            return url.replace("capacitor://localhost/_capacitor_file_", "");
        }

        // Handle file:// URI format
        if (url.startsWith("file://")) {
            return url.substring(7); // Remove "file://" prefix
        }

        // Return as-is for direct file paths
        return url;
    }

    /**
     * Check if the URL is a remote URL (HTTP/HTTPS)
     */
    private boolean isRemoteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        return url.startsWith("http://") || url.startsWith("https://");
    }

    /**
     * Detect MIME type from file extension
     */
    private String detectMimeType(String url, String normalizedPath) {
        // Extract file extension from URL or path
        String extension = "";
        String source = url != null && !url.isEmpty() ? url : normalizedPath;

        int lastDot = source.lastIndexOf('.');
        if (lastDot > 0 && lastDot < source.length() - 1) {
            extension = source.substring(lastDot + 1).toLowerCase();
        }

        // Map extension to MIME type
        return switch (extension) {
            case "m4a" -> "audio/m4a";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "aac" -> "audio/aac";
            case "ogg" -> "audio/ogg";
            case "flac" -> "audio/flac";
            case "mp4" -> "audio/mp4";
            default -> "audio/*";
        };
    }

    /**
     * Get file size for local files
     */
    private long getFileSize(String normalizedPath) {
        try {
            // Only get size for local files, not remote URLs
            if (normalizedPath != null && !normalizedPath.startsWith("http://") && !normalizedPath.startsWith("https://")) {
                File file = new File(normalizedPath);
                if (file.exists()) {
                    return file.length();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get file size: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Play a track (or resume current track if no URL specified)
     */
    void playTrack(String url) {
        try {
            // If no URL specified, resume current track
            if (url == null || url.isEmpty()) {
                if (currentTrackUrl != null) {
                    resumeTrack(currentTrackUrl);
                } else {
                    if (callback != null) {
                        callback.onError("", "No track specified and no current track");
                    }
                }
                return;
            }

            // Check if track is preloaded
            TrackInfo trackInfo = preloadedTracks.get(url);
            if (trackInfo == null || !trackInfo.isLoaded) {
                if (callback != null) {
                    callback.onError(generateTrackId(url), "Track not preloaded: " + url);
                }
                return;
            }

            // If different track is playing, stop it first
            if (currentTrackUrl != null && !currentTrackUrl.equals(url)) {
                pauseCurrentTrack();
            }

            // Request audio focus
            if (requestAudioFocus()) {
                Log.w(TAG, "Failed to gain audio focus, but continuing with playback");
            }

            MediaPlayer player = trackInfo.player;
            if (player == null) {
                if (callback != null) {
                    callback.onError(trackInfo.trackId, "MediaPlayer is null");
                }
                return;
            }

            // Start playback
            if (!player.isPlaying()) {
                player.start();
                currentTrackUrl = url;
                updateStatus(PlaybackStatus.PLAYING);
                startProgressMonitoring(trackInfo);

                Log.d(TAG, "Started playing track: " + url);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error playing track: " + url, e);
            if (callback != null && url != null) {
                callback.onError(generateTrackId(url), "Error playing track: " + e.getMessage());
            }
        }
    }

    /**
     * Pause current track or specific track
     */
    void pauseTrack(String url) {
        try {
            String targetUrl = (url == null || url.isEmpty()) ? currentTrackUrl : url;

            if (targetUrl == null) {
                Log.w(TAG, "No track to pause");
                return;
            }

            TrackInfo trackInfo = preloadedTracks.get(targetUrl);
            if (trackInfo == null || trackInfo.player == null) {
                Log.w(TAG, "Track not found or player is null: " + targetUrl);
                return;
            }

            MediaPlayer player = trackInfo.player;
            if (player.isPlaying()) {
                player.pause();
                updateStatus(PlaybackStatus.PAUSED);
                stopProgressMonitoring();

                Log.d(TAG, "Paused track: " + targetUrl);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error pausing track", e);
            if (callback != null && url != null) {
                callback.onError(generateTrackId(url), "Error pausing track: " + e.getMessage());
            }
        }
    }

    /**
     * Resume current track or specific track
     */
    void resumeTrack(String url) {
        try {
            String targetUrl = (url == null || url.isEmpty()) ? currentTrackUrl : url;

            if (targetUrl == null) {
                Log.w(TAG, "No track to resume");
                return;
            }

            TrackInfo trackInfo = preloadedTracks.get(targetUrl);
            if (trackInfo == null || trackInfo.player == null) {
                Log.w(TAG, "Track not found or player is null: " + targetUrl);
                return;
            }

            // Request audio focus
            if (requestAudioFocus()) {
                Log.w(TAG, "Failed to gain audio focus, but continuing with playback");
            }

            MediaPlayer player = trackInfo.player;
            if (!player.isPlaying()) {
                player.start();
                currentTrackUrl = targetUrl;
                updateStatus(PlaybackStatus.PLAYING);
                startProgressMonitoring(trackInfo);

                Log.d(TAG, "Resumed track: " + targetUrl);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error resuming track", e);
            if (callback != null && url != null) {
                callback.onError(generateTrackId(url), "Error resuming track: " + e.getMessage());
            }
        }
    }

    /**
     * Stop current track or specific track
     */
    void stopTrack(String url) {
        try {
            String targetUrl = (url == null || url.isEmpty()) ? currentTrackUrl : url;

            if (targetUrl == null) {
                Log.w(TAG, "No track to stop");
                return;
            }

            TrackInfo trackInfo = preloadedTracks.get(targetUrl);
            if (trackInfo == null || trackInfo.player == null) {
                Log.w(TAG, "Track not found or player is null: " + targetUrl);
                return;
            }

            MediaPlayer player = trackInfo.player;
            if (player.isPlaying()) {
                player.pause();
            }

            player.seekTo(0);

            if (currentTrackUrl != null && currentTrackUrl.equals(targetUrl)) {
                currentTrackUrl = null;
                updateStatus(PlaybackStatus.IDLE);
                stopProgressMonitoring();
                abandonAudioFocus();
            }

            Log.d(TAG, "Stopped track: " + targetUrl);

        } catch (Exception e) {
            Log.e(TAG, "Error stopping track", e);
            if (callback != null && url != null) {
                callback.onError(generateTrackId(url), "Error stopping track: " + e.getMessage());
            }
        }
    }

    /**
     * Seek to position in track
     */
    void seekTrack(int seconds, String url) {
        try {
            String targetUrl = (url == null || url.isEmpty()) ? currentTrackUrl : url;

            if (targetUrl == null) {
                Log.w(TAG, "No track to seek");
                return;
            }

            TrackInfo trackInfo = preloadedTracks.get(targetUrl);
            if (trackInfo == null || trackInfo.player == null) {
                Log.w(TAG, "Track not found or player is null: " + targetUrl);
                return;
            }

            MediaPlayer player = trackInfo.player;
            int positionMs = seconds * 1000;
            player.seekTo(positionMs);

            Log.d(TAG, "Seeked track to " + seconds + " seconds: " + targetUrl);

        } catch (Exception e) {
            Log.e(TAG, "Error seeking track", e);
            if (callback != null && url != null) {
                callback.onError(generateTrackId(url), "Error seeking track: " + e.getMessage());
            }
        }
    }

    /**
     * Get current playback info
     */
    PlaybackInfo getPlaybackInfo() {
        if (currentTrackUrl == null) {
            return new PlaybackInfo(null, null, 0, 0, 0, false);
        }

        TrackInfo trackInfo = preloadedTracks.get(currentTrackUrl);
        if (trackInfo == null || trackInfo.player == null) {
            return new PlaybackInfo(null, null, 0, 0, 0, false);
        }

        MediaPlayer player = trackInfo.player;
        int currentPosition = player.getCurrentPosition() / 1000; // Convert to seconds
        int duration = player.getDuration() / 1000; // Convert to seconds
        boolean isPlaying = player.isPlaying();

        return new PlaybackInfo(
            trackInfo.trackId,
            trackInfo.url,
            0, // currentIndex - simplified for single track
            currentPosition,
            duration,
            isPlaying
        );
    }

    /**
     * Destroy all playback resources and reinitialize
     */
    void destroy() {
        Log.d(TAG, "Destroying playback manager");

        // Stop progress monitoring
        stopProgressMonitoring();

        // Release all MediaPlayer instances
        for (TrackInfo trackInfo : preloadedTracks.values()) {
            if (trackInfo.player != null) {
                try {
                    if (trackInfo.player.isPlaying()) {
                        trackInfo.player.stop();
                    }
                    trackInfo.player.reset();
                    trackInfo.player.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing player", e);
                }
            }
        }

        // Clear all tracks
        preloadedTracks.clear();

        // Abandon audio focus
        abandonAudioFocus();

        // Reset state
        currentTrackUrl = null;
        currentStatus = PlaybackStatus.IDLE;

        Log.d(TAG, "Playback manager destroyed and reinitialized");
    }

    // Private helper methods

    private void pauseCurrentTrack() {
        if (currentTrackUrl != null) {
            pauseTrack(currentTrackUrl);
        }
    }

    private void updateStatus(PlaybackStatus newStatus) {
        if (currentStatus != newStatus) {
            currentStatus = newStatus;

            String statusString = statusToString(newStatus);
            int position;

            if (currentTrackUrl != null) {
                TrackInfo trackInfo = preloadedTracks.get(currentTrackUrl);
                if (trackInfo != null && trackInfo.player != null) {
                    position = trackInfo.player.getCurrentPosition() / 1000;
                } else {
                    position = 0;
                }
            } else {
                position = 0;
            }

            mainHandler.post(() -> {
                if (callback != null) {
                    callback.onStatusChanged(
                        statusString,
                        currentTrackUrl != null ? currentTrackUrl : "",
                        position
                    );
                }
            });
        }
    }

    private String statusToString(PlaybackStatus status) {
        return switch (status) {
            case PLAYING -> "playing";
            case PAUSED -> "paused";
            case LOADING -> "loading";
            default -> "idle";
        };
    }

    private String generateTrackId(String url) {
        // Generate a simple track ID based on URL hash
        return "track_" + Math.abs(url.hashCode());
    }

    // Progress monitoring

    private void startProgressMonitoring(TrackInfo trackInfo) {
        stopProgressMonitoring();

        isProgressMonitoring = true;
        progressTimer = new Timer();
        progressTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isProgressMonitoring) return;

                MediaPlayer player = trackInfo.player;
                if (player == null) {
                    stopProgressMonitoring();
                    return;
                }

                try {
                    // Only emit progress events when player is actually playing
                    if (!player.isPlaying()) {
                        return;
                    }

                    int currentPosition = player.getCurrentPosition() / 1000; // seconds
                    int duration = player.getDuration() / 1000; // seconds

                    mainHandler.post(() -> {
                        if (callback != null) {
                            callback.onProgress(
                                trackInfo.trackId,
                                trackInfo.url,
                                currentPosition,
                                duration,
                                true // isPlaying is always true here since we checked above
                            );
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Error getting playback position", e);
                }
            }
        }, 0, PROGRESS_UPDATE_INTERVAL_MS);
    }

    private void stopProgressMonitoring() {
        if (progressTimer != null) {
            progressTimer.cancel();
            progressTimer = null;
        }
        isProgressMonitoring = false;
    }

    // Audio focus management

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;

        try {
            int result;
            if (audioFocusRequest != null) {
                result = audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                return true;
            }
            hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
            if (hasAudioFocus) {
                Log.d(TAG, "Audio focus granted for playback");
            } else {
                Log.w(TAG, "Audio focus denied for playback");
            }
            return !hasAudioFocus;
        } catch (Exception e) {
            Log.e(TAG, "Error requesting audio focus", e);
            return true;
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
                // Audio focus gained - resume if we were playing
                Log.d(TAG, "Audio focus gained");
                if (currentStatus == PlaybackStatus.PAUSED) {
                    resumeTrack(currentTrackUrl);
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Permanent loss - stop playback
                Log.w(TAG, "Audio focus lost permanently");
                if (currentTrackUrl != null) {
                    pauseTrack(currentTrackUrl);
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Temporary loss - pause playback
                Log.w(TAG, "Audio focus lost temporarily");
                if (currentStatus == PlaybackStatus.PLAYING) {
                    pauseTrack(currentTrackUrl);
                }
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Can duck - lower volume (handled by system)
                Log.d(TAG, "Audio focus loss can duck");
                break;

            default:
                Log.w(TAG, "Unknown audio focus change: " + focusChange);
                break;
        }
    }

    // Supporting classes

    interface PreloadCallback {
        void onSuccess(String url, String mimeType, int duration, long size);
        void onError(String url, String message);
    }

    static class PlaybackInfo {
        String trackId;
        String url;
        int currentIndex;
        int currentPosition;
        int duration;
        boolean isPlaying;

        PlaybackInfo(String trackId, String url, int currentIndex, int currentPosition, int duration, boolean isPlaying) {
            this.trackId = trackId;
            this.url = url;
            this.currentIndex = currentIndex;
            this.currentPosition = currentPosition;
            this.duration = duration;
            this.isPlaying = isPlaying;
        }
    }
}

