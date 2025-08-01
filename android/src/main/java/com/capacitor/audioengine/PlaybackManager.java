package com.capacitor.audioengine;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import java.util.ArrayList;
import java.util.List;

public class PlaybackManager implements Player.Listener, AudioManager.OnAudioFocusChangeListener {
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
    private ExoPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private MediaSessionCompat mediaSession;

    // Playlist management
    private List<AudioTrack> playlist;
    private int currentIndex = 0;
    private PlaybackStatus status = PlaybackStatus.IDLE;

  // Media sources
    private ConcatenatingMediaSource concatenatingMediaSource;
    private DefaultDataSourceFactory dataSourceFactory;

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
        // Initialize ExoPlayer
        player = new ExoPlayer.Builder(context).build();
        player.addListener(this);

        // Initialize AudioManager
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        // Initialize data source factory
        dataSourceFactory = new DefaultDataSourceFactory(context,
            Util.getUserAgent(context, "CapacitorAudioEngine"));

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

    public void preloadTracks(List<String> trackUrls) throws Exception {
        if (trackUrls == null || trackUrls.isEmpty()) {
            throw new Exception("Track URLs list cannot be empty");
        }

        // Convert URLs to AudioTrack objects
      List<AudioTrack> tracks = getAudioTracks(trackUrls);

      // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> {
                try {
                    initPlaylistOnMainThread(tracks);
                } catch (Exception e) {
                    if (listener != null) {
                        listener.onPlaybackError("Failed to preload tracks: " + e.getMessage());
                    }
                }
            });
            return;
        }

        initPlaylistOnMainThread(tracks);
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

        // Create concatenating media source
        concatenatingMediaSource = new ConcatenatingMediaSource();

        for (AudioTrack track : tracks) {
            MediaItem mediaItem = MediaItem.fromUri(track.getUrl());
            MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem);
            concatenatingMediaSource.addMediaSource(mediaSource);
        }

        // Prepare player
        player.setMediaSource(concatenatingMediaSource);
        player.prepare();

        this.status = PlaybackStatus.IDLE;

        // Update media session metadata
        updateMediaSessionMetadata();
    }

    public void play() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::play);
            return;
        }

        if (status == PlaybackStatus.IDLE || status == PlaybackStatus.PAUSED || status == PlaybackStatus.STOPPED) {
            if (requestAudioFocus()) {
                player.setPlayWhenReady(true);
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

    public void pause() {
        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::pause);
            return;
        }

        player.setPlayWhenReady(false);
        status = PlaybackStatus.PAUSED;

        AudioTrack currentTrack = getCurrentTrack();
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

        player.setPlayWhenReady(false);
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

        long milliseconds = (long) (seconds * 1000);
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
            player.seekTo(currentIndex, 0);

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
            player.seekTo(currentIndex, 0);

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
            player.seekTo(currentIndex, 0);

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
        long duration = player.getDuration();
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

        // Switch to the track and play
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

        // Switch to the track and resume/play
        currentIndex = trackIndex;
        switchToTrack(trackIndex);
        play();
    }

    public void stopByUrl(String url) {
        int trackIndex = findTrackIndex(url);
        if (trackIndex == -1) {
            Log.w(TAG, "Track with URL '" + url + "' not found in preloaded tracks");
            return;
        }

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

            player.seekTo(index, 0);

            AudioTrack newTrack = playlist.get(index);
            if (listener != null) {
                listener.onTrackChanged(newTrack, index);
            }
        }
    }

    public void release() {
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

    // ExoPlayer.Listener implementation
    @Override
    public void onPlaybackStateChanged(int playbackState) {
        switch (playbackState) {
            case Player.STATE_ENDED:
                onTrackCompleted();
                break;
            case Player.STATE_READY:
                // Player is ready to play
                break;
            case Player.STATE_BUFFERING:
                // Player is buffering
                break;
            case Player.STATE_IDLE:
                // Player is idle
                break;
        }
        updatePlaybackState();
    }

    @Override
    public void onPlayerError(@NonNull com.google.android.exoplayer2.PlaybackException error) {
        if (listener != null) {
            listener.onPlaybackError("Playback error: " + error.getMessage());
        }
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
                long currentPosition = player.getCurrentPosition();
                long duration = player.getDuration();
                boolean isPlaying = (status == PlaybackStatus.PLAYING);

                listener.onPlaybackProgress(currentTrack, currentIndex, currentPosition, duration, isPlaying);
            }
        }
    }

    private void emitStatusChange() {
        if (listener != null) {
            AudioTrack currentTrack = getCurrentTrack();
            long currentPosition = player != null ? player.getCurrentPosition() : 0;
            long duration = player != null ? player.getDuration() : 0;
            boolean isPlaying = (status == PlaybackStatus.PLAYING);

            listener.onPlaybackStatusChanged(currentTrack, currentIndex, status, currentPosition, duration, isPlaying);
        }
    }
}

