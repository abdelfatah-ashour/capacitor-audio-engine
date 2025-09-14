package com.capacitor.audioengine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;

import com.getcapacitor.JSObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class PlaybackManager implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, AudioManager.OnAudioFocusChangeListener {
    private static final String TAG = "PlaybackManager";

    public interface PlaybackManagerListener {
        void onTrackChanged(AudioTrack track, int index);
        void onTrackEnded(AudioTrack track, int index);
        void onPlaybackStarted(AudioTrack track, int index);
        void onPlaybackPaused(AudioTrack track, int index);
        void onPlaybackError(String error);
        void onPlaybackProgress(AudioTrack track, int index, long currentPosition, long duration, boolean isPlaying);
        void onPlaybackStatusChanged(AudioTrack track, int index, PlaybackStatus status, long currentPosition, long duration, boolean isPlaying);
    }

    private final Context context;
    private final PlaybackManagerListener listener;
    private final Handler mainHandler;

    // Audio components
    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaSessionCompat mediaSession;

    // Playlist management
    private List<AudioTrack> playlist;
    private int currentIndex = 0;
    private PlaybackStatus status = PlaybackStatus.IDLE;
    private HashMap<String, Long> trackPositions = new HashMap<>();

    // Progress tracking
    private static final long PROGRESS_UPDATE_INTERVAL = 500; // 500ms
    private Runnable progressUpdateRunnable;
    private boolean isTrackingProgress = false;

    public PlaybackManager(Context context, PlaybackManagerListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.playlist = new ArrayList<>();

        // Initialize progress tracking runnable
        this.progressUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isTrackingProgress && player != null) {
                    updateProgress();
                    mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
                }
            }
        };

        initializeComponents();
    }

    private void initializeComponents() {
        // Initialize MediaPlayer
        player = new MediaPlayer();
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);

        // Initialize AudioManager
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Initialize media session
        setupMediaSession();

        // Setup audio focus request for Android O and above
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(this)
            .build();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(context, TAG);
        mediaSession.setActive(true);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                play();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                skipToNext();
            }

            @Override
            public void onSkipToPrevious() {
                skipToPrevious();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((double) pos / 1000.0);
            }
        });
    }

    public List<JSObject> preloadTracks(List<String> trackUrls) throws Exception {
        return preloadTracks(trackUrls, true); // Default preloadNext to true
    }

    public List<JSObject> preloadTracks(List<String> trackUrls, boolean preloadNext) throws Exception {
        if (trackUrls == null || trackUrls.isEmpty()) {
            throw new Exception("Track URLs list cannot be empty");
        }

        // Convert URLs to AudioTrack objects and get track information
        List<AudioTrack> tracks = getAudioTracks(trackUrls);
        List<JSObject> trackResults = new ArrayList<>();

        // Ensure we're on the main thread for player operations
        if (Looper.myLooper() != Looper.getMainLooper()) {
            CountDownLatch latch = new CountDownLatch(1);
            mainHandler.post(() -> {
                try {
                    List<JSObject> results = initPlaylistOnMainThreadWithResults(tracks, preloadNext);
                    trackResults.addAll(results);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onPlaybackError("Failed to preload tracks: " + e.getMessage());
                    }
                } finally {
                    latch.countDown();
                }
            });

            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("Preload operation was interrupted");
            }

            return trackResults;
        }

        return initPlaylistOnMainThreadWithResults(tracks, preloadNext);
    }

  @NonNull
  private static List<AudioTrack> getAudioTracks(List<String> trackUrls) {
    List<AudioTrack> tracks = new ArrayList<>();
    for (int i = 0; i < trackUrls.size(); i++) {
        String url = trackUrls.get(i);
        // Create AudioTrack with URL as both id and url, no additional metadata
        AudioTrack track = new AudioTrack(
            "track_" + i,  // Generate ID
            url,          // URL
            null,         // title
            null,         // artist
            null          // artworkUrl
        );
        tracks.add(track);
    }
    return tracks;
  }

    private void initPlaylistOnMainThread(List<AudioTrack> tracks) throws Exception {
        this.playlist = new ArrayList<>(tracks);
        this.currentIndex = 0;
        this.status = PlaybackStatus.LOADING;

        // For MediaPlayer, we'll load the first track
        if (!tracks.isEmpty()) {
            loadCurrentTrack();
        }

        this.status = PlaybackStatus.IDLE;

        // Update media session metadata
        updateMediaSessionMetadata();
    }

    private List<JSObject> initPlaylistOnMainThreadWithResults(List<AudioTrack> tracks, boolean preloadNext) throws Exception {
        List<JSObject> trackResults = new ArrayList<>();
        List<AudioTrack> validTracks = new ArrayList<>();

        this.currentIndex = 0;
        this.status = PlaybackStatus.LOADING;

        // Process each track and collect information
        for (AudioTrack track : tracks) {
            JSObject trackInfo = new JSObject();
            trackInfo.put("url", track.getUrl());

            try {
                // Try to extract basic metadata
                String mimeType = getMimeTypeFromUrl(track.getUrl());
                if (mimeType != null) {
                    trackInfo.put("mimeType", mimeType);
                }

                // Get duration using MediaMetadataRetriever for better accuracy
                double duration = extractTrackDuration(track.getUrl());
                if (duration > 0) {
                    trackInfo.put("duration", duration);
                } else {
                    trackInfo.put("duration", 0);
                }

                // Get file size for local files
                if (track.getUrl().startsWith("file://")) {
                    try {
                        File file = new File(track.getUrl().replace("file://", ""));
                        if (file.exists()) {
                            trackInfo.put("size", file.length());
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Could not get file size for: " + track.getUrl());
                    }
                }

                // Add to valid collections
                validTracks.add(track);
                trackInfo.put("loaded", true);

            } catch (Exception e) {
                Log.w(TAG, "Failed to load track: " + track.getUrl(), e);
                trackInfo.put("loaded", false);
                trackInfo.put("duration", 0);
            }

            trackResults.add(trackInfo);
        }

        // Update playlist with valid tracks
        this.playlist = validTracks;

        // For MediaPlayer, we'll load the first track
        if (!validTracks.isEmpty()) {
            loadCurrentTrack();
            this.status = PlaybackStatus.IDLE;
            updateMediaSessionMetadata();
        } else {
            this.status = PlaybackStatus.IDLE;
        }

        return trackResults;
    }

    private void loadCurrentTrack() {
        if (playlist == null || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }

        AudioTrack track = playlist.get(currentIndex);
        try {
            player.reset();
            player.setDataSource(track.getUrl());
            player.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load track: " + track.getUrl(), e);
            if (listener != null) {
                listener.onPlaybackError("Failed to load track: " + e.getMessage());
            }
        }
    }

    private String getMimeTypeFromUrl(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // For remote URLs, try to determine from extension or use URLConnection
                String extension = getFileExtension(url);
                return getMimeTypeFromExtension(extension);
            } else if (url.startsWith("file://")) {
                // For local files, use file extension
                String extension = getFileExtension(url);
                return getMimeTypeFromExtension(extension);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not determine MIME type for: " + url);
        }
        return "audio/mp4"; // Default for Android
    }

    private String getFileExtension(String url) {
        try {
            int lastDotIndex = url.lastIndexOf('.');
            int lastSlashIndex = url.lastIndexOf('/');
            if (lastDotIndex > lastSlashIndex && lastDotIndex > 0) {
                return url.substring(lastDotIndex + 1).toLowerCase();
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract file extension from: " + url);
        }
        return "";
    }

    private String getMimeTypeFromExtension(String extension) {
        switch (extension.toLowerCase()) {
            case "mp3":
                return "audio/mpeg";
            case "m4a":
            case "mp4":
                return "audio/mp4";
            case "aac":
                return "audio/aac";
            case "wav":
                return "audio/wav";
            case "flac":
                return "audio/flac";
            case "ogg":
                return "audio/ogg";
            default:
                return "audio/mp4"; // Default for Android
        }
    }

    private double extractTrackDuration(String url) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();

            if (url.startsWith("http://") || url.startsWith("https://")) {
                // For remote URLs
                retriever.setDataSource(url, new HashMap<>());
            } else if (url.startsWith("file://")) {
                // For file URIs, remove file:// prefix
                String filePath = url.replace("file://", "");
                retriever.setDataSource(filePath);
            } else {
                // Assume it's a local file path
                retriever.setDataSource(url);
            }

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                return durationMs / 1000.0; // Convert to seconds
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not extract duration for: " + url, e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    Log.w(TAG, "Failed to release MediaMetadataRetriever", e);
                }
            }
        }
        return 0;
    }

    public void play() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::play);
            return;
        }

        if (status == PlaybackStatus.IDLE || status == PlaybackStatus.PAUSED || status == PlaybackStatus.STOPPED) {
            if (requestAudioFocus()) {
                if (status == PlaybackStatus.IDLE) {
                    loadCurrentTrack();
                } else {
                    player.start();
                    status = PlaybackStatus.PLAYING;

                    AudioTrack currentTrack = getCurrentTrack();
                    if (currentTrack != null && listener != null) {
                        listener.onPlaybackStarted(currentTrack, currentIndex);
                    }

                    // Start progress tracking
                    startProgressTracking();

                    updatePlaybackState();
                    emitStatusChange();
                }
            }
        }
    }

    public void pause() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::pause);
            return;
        }

        // Store current position before pausing
        AudioTrack currentTrack = getCurrentTrack();
        if (currentTrack != null && player != null) {
            long currentPosition = player.getCurrentPosition();
            trackPositions.put(currentTrack.getUrl(), currentPosition);
        }

        player.pause();
        status = PlaybackStatus.PAUSED;

        if (currentTrack != null && listener != null) {
            listener.onPlaybackPaused(currentTrack, currentIndex);
        }

        // Stop progress tracking
        stopProgressTracking();

        updatePlaybackState();
        emitStatusChange();
    }

    public void resume() {
        play();
    }

    public void stop() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::stop);
            return;
        }

        // Clear stored position when stopping
        AudioTrack currentTrack = getCurrentTrack();
        if (currentTrack != null) {
            trackPositions.remove(currentTrack.getUrl());
        }

        player.stop();
        player.seekTo(0);
        status = PlaybackStatus.STOPPED;

        // Stop progress tracking
        stopProgressTracking();

        abandonAudioFocus();
        updatePlaybackState();
        emitStatusChange();
    }

    public void seekTo(double seconds) {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> seekTo(seconds));
            return;
        }

        int milliseconds = (int) (seconds * 1000);
        player.seekTo(milliseconds);
    }

    public void skipToNext() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::skipToNext);
            return;
        }

        if (currentIndex < playlist.size() - 1) {
            currentIndex++;
            loadCurrentTrack();

            AudioTrack currentTrack = getCurrentTrack();
            if (currentTrack != null && listener != null) {
                listener.onTrackChanged(currentTrack, currentIndex);
            }

            updateMediaSessionMetadata();
        }
    }

    public void skipToPrevious() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::skipToPrevious);
            return;
        }

        if (currentIndex > 0) {
            currentIndex--;
            loadCurrentTrack();

            AudioTrack currentTrack = getCurrentTrack();
            if (currentTrack != null && listener != null) {
                listener.onTrackChanged(currentTrack, currentIndex);
            }

            updateMediaSessionMetadata();
        }
    }

    public void skipToIndex(int index) {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> skipToIndex(index));
            return;
        }

        if (index >= 0 && index < playlist.size()) {
            currentIndex = index;
            loadCurrentTrack();

            AudioTrack currentTrack = getCurrentTrack();
            if (currentTrack != null && listener != null) {
                listener.onTrackChanged(currentTrack, currentIndex);
            }

            updateMediaSessionMetadata();
        }
    }

    public AudioTrack getCurrentTrack() {
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            return playlist.get(currentIndex);
        }
        return null;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public double getCurrentPosition() {
        return player.getCurrentPosition() / 1000.0;
    }

    public double getDuration() {
        int duration = player.getDuration();
        return duration != -1 ? duration / 1000.0 : 0.0;
    }

    public boolean isPlaying() {
        return status == PlaybackStatus.PLAYING;
    }

    public PlaybackStatus getStatus() {
        return status;
    }

    // Per-URL Playback Methods

    public void playByUrl(String url) throws Exception {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            throw new Exception("Track with URL '" + url + "' not found in preloaded tracks");
        }

        // Clear any stored position for this track since we're starting fresh
        trackPositions.remove(url);

        // Switch to the track and play from beginning
        currentIndex = trackIndex;
        switchToTrack(trackIndex);
        play();
    }

    public void pauseByUrl(String url) {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            Log.w(TAG, "Track with URL '" + url + "' not found in preloaded tracks");
            return;
        }

        // Only pause if this is the currently playing track
        if (trackIndex == currentIndex) {
            pause();
        }
    }

    public void resumeByUrl(String url) {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            Log.w(TAG, "Track with URL '" + url + "' not found in preloaded tracks");
            return;
        }

        // Check if we're resuming the same track that's currently selected
        if (trackIndex == currentIndex) {
            // Same track - just resume without switching
            play();
        } else {
            // Different track - switch to it and restore its position if available
            currentIndex = trackIndex;
            switchToTrackWithPosition(trackIndex);
            play();
        }
    }

    public void stopByUrl(String url) {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            Log.w(TAG, "Track with URL '" + url + "' not found in preloaded tracks");
            return;
        }

        // Clear stored position for this track
        trackPositions.remove(url);

        // Only stop if this is the currently playing track
        if (trackIndex == currentIndex) {
            stop();
        }
    }

    public void seekByUrl(String url, double seconds) {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            Log.w(TAG, "Track with URL '" + url + "' not found in preloaded tracks");
            return;
        }

        // Switch to the track and seek
        currentIndex = trackIndex;
        switchToTrack(trackIndex);
        seekTo(seconds);
    }

    // Helper Methods

    private int findTrackIndex(String url) {
        if (playlist == null) {
            return -1;
        }

        for (int i = 0; i < playlist.size(); i++) {
            if (playlist.get(i).getUrl().equals(url)) {
                return i;
            }
        }
        return -1;
    }

    private void switchToTrack(int index) {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> switchToTrack(index));
            return;
        }

        if (player != null && index >= 0 && index < playlist.size()) {
            // Reset status to allow proper playback control after track switch
            if (status == PlaybackStatus.PLAYING) {
                status = PlaybackStatus.PAUSED;
            }

            loadCurrentTrack();

            AudioTrack newTrack = playlist.get(index);
            if (listener != null) {
                listener.onTrackChanged(newTrack, index);
            }
        }
    }

    private void switchToTrackWithPosition(int index) {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> switchToTrackWithPosition(index));
            return;
        }

        if (player != null && index >= 0 && index < playlist.size()) {
            // Reset status to allow proper playback control after track switch
            if (status == PlaybackStatus.PLAYING) {
                status = PlaybackStatus.PAUSED;
            }

            AudioTrack newTrack = playlist.get(index);

            // Load the track first
            loadCurrentTrack();

            // Check if we have a stored position for this track
            Long storedPosition = trackPositions.get(newTrack.getUrl());
            if (storedPosition != null) {
                // Seek to the stored position after the track is loaded
                player.seekTo(storedPosition.intValue());
            }

            if (listener != null) {
                listener.onTrackChanged(newTrack, index);
            }
        }
    }

    public void release() {
        // Clear all stored positions
        if (trackPositions != null) {
            trackPositions.clear();
        }

        if (player != null) {
            player.release();
            player = null;
        }

        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }

        abandonAudioFocus();
    }

    // MediaPlayer listener implementations
    @Override
    public void onPrepared(MediaPlayer mp) {
        // Player is ready to play
        updatePlaybackState();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        onTrackCompleted();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (listener != null) {
            listener.onPlaybackError("Playback error: " + what + ", " + extra);
        }
        return true; // Error handled
    }

    private void onTrackCompleted() {
        AudioTrack currentTrack = getCurrentTrack();
        if (currentTrack != null && listener != null) {
            listener.onTrackEnded(currentTrack, currentIndex);
        }

        // Auto-advance to next track
        if (currentIndex < playlist.size() - 1) {
            skipToNext();
        } else {
            status = PlaybackStatus.STOPPED;
        }
    }

    // Audio focus handling
    private boolean requestAudioFocus() {
        int result;
      result = audioManager.requestAudioFocus(audioFocusRequest);
      return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
      audioManager.abandonAudioFocusRequest(audioFocusRequest);
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                // Resume playback if it was paused due to focus loss
                if (status == PlaybackStatus.PAUSED) {
                    play();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Pause playback
                if (status == PlaybackStatus.PLAYING) {
                    pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lower volume or pause (we'll pause for simplicity)
                if (status == PlaybackStatus.PLAYING) {
                    pause();
                }
                break;
        }
    }

    private void updateMediaSessionMetadata() {
        AudioTrack currentTrack = getCurrentTrack();
        if (currentTrack == null) return;

        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTrack.getTitle())
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentTrack.getArtist())
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long) (getDuration() * 1000));

        mediaSession.setMetadata(metadataBuilder.build());
    }

    private void updatePlaybackState() {
        int state = switch (status) {
          case PLAYING -> PlaybackStateCompat.STATE_PLAYING;
          case PAUSED -> PlaybackStateCompat.STATE_PAUSED;
          case STOPPED -> PlaybackStateCompat.STATE_STOPPED;
          default -> PlaybackStateCompat.STATE_NONE;
        };

      PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
            .setState(state, (long) (getCurrentPosition() * 1000), 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY |
                       PlaybackStateCompat.ACTION_PAUSE |
                       PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                       PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                       PlaybackStateCompat.ACTION_SEEK_TO)
            .build();

        mediaSession.setPlaybackState(playbackState);
    }

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
        if (player != null && listener != null) {
            AudioTrack currentTrack = getCurrentTrack();
            if (currentTrack != null) {
                int currentPosition = player.getCurrentPosition();
                int duration = player.getDuration();
                boolean isPlaying = (status == PlaybackStatus.PLAYING);

                listener.onPlaybackProgress(currentTrack, currentIndex, currentPosition, duration, isPlaying);
            }
        }
    }

    private void emitStatusChange() {
        if (listener != null) {
            AudioTrack currentTrack = getCurrentTrack();
            int currentPosition = player != null ? player.getCurrentPosition() : 0;
            int duration = player != null ? player.getDuration() : 0;
            boolean isPlaying = (status == PlaybackStatus.PLAYING);

            listener.onPlaybackStatusChanged(currentTrack, currentIndex, status, currentPosition, duration, isPlaying);
        }
    }
}

