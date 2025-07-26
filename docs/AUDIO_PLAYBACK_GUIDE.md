# Audio Playback Manager Example

This example demonstrates how to use the new Audio Playback Manager features in the Capacitor Audio Engine plugin.

## Setup

```typescript
import { CapacitorAudioEngine } from 'capacitor-audio-engine';

// Example playlist
const playlist = [
  {
    id: '1',
    url: 'https://example.com/song1.mp3',
    title: 'Song One',
    artist: 'Artist One',
    artworkUrl: 'https://example.com/artwork1.jpg',
  },
  {
    id: '2',
    url: 'https://example.com/song2.mp3',
    title: 'Song Two',
    artist: 'Artist Two',
    artworkUrl: 'https://example.com/artwork2.jpg',
  },
  {
    id: '3',
    url: 'https://example.com/song3.mp3',
    title: 'Song Three',
    artist: 'Artist Three',
    artworkUrl: 'https://example.com/artwork3.jpg',
  },
];
```

## Basic Usage

### Initialize Playlist

```typescript
// Initialize playlist with preloading enabled
await CapacitorAudioEngine.initPlaylist({
  tracks: playlist,
  preloadNext: true,
});
```

### Playback Controls

```typescript
// Play current track
await CapacitorAudioEngine.playAudio();

// Pause playback
await CapacitorAudioEngine.pauseAudio();

// Resume playback
await CapacitorAudioEngine.resumeAudio();

// Stop playback
await CapacitorAudioEngine.stopAudio();
```

### Navigation

```typescript
// Skip to next track
await CapacitorAudioEngine.skipToNext();

// Skip to previous track
await CapacitorAudioEngine.skipToPrevious();

// Jump to specific track (zero-based index)
await CapacitorAudioEngine.skipToIndex({ index: 2 });
```

### Seeking

```typescript
// Seek to 30 seconds
await CapacitorAudioEngine.seekAudio({ seconds: 30 });
```

### Get Playback Information

```typescript
const info = await CapacitorAudioEngine.getPlaybackInfo();
console.log('Current track:', info.currentTrack);
console.log('Current index:', info.currentIndex);
console.log('Current position:', info.currentPosition);
console.log('Duration:', info.duration);
console.log('Is playing:', info.isPlaying);
console.log('Status:', info.status);
```

## Event Listeners

```typescript
// Listen for track changes
CapacitorAudioEngine.addListener('trackChanged', (event) => {
  console.log('Track changed:', event.track.title, 'at index', event.index);
});

// Listen for track end
CapacitorAudioEngine.addListener('trackEnded', (event) => {
  console.log('Track ended:', event.track.title);
});

// Listen for playback start
CapacitorAudioEngine.addListener('playbackStarted', (event) => {
  console.log('Playback started:', event.track.title);
});

// Listen for playback pause
CapacitorAudioEngine.addListener('playbackPaused', (event) => {
  console.log('Playback paused:', event.track.title, 'at position', event.position);
});

// Listen for playback errors
CapacitorAudioEngine.addListener('playbackError', (event) => {
  console.error('Playback error:', event.message);
});
```

## Complete Example Component (React)

```typescript
import React, { useState, useEffect } from 'react';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import type { AudioTrack, PlaybackInfo } from 'capacitor-audio-engine';

const AudioPlayer: React.FC = () => {
  const [playbackInfo, setPlaybackInfo] = useState<PlaybackInfo | null>(null);
  const [isInitialized, setIsInitialized] = useState(false);

  const playlist: AudioTrack[] = [
    {
      id: '1',
      url: 'https://example.com/song1.mp3',
      title: 'Song One',
      artist: 'Artist One',
      artworkUrl: 'https://example.com/artwork1.jpg'
    },
    {
      id: '2',
      url: 'https://example.com/song2.mp3',
      title: 'Song Two',
      artist: 'Artist Two',
      artworkUrl: 'https://example.com/artwork2.jpg'
    }
  ];

  useEffect(() => {
    initializePlaylist();
    setupEventListeners();

    return () => {
      CapacitorAudioEngine.removeAllListeners();
    };
  }, []);

  const initializePlaylist = async () => {
    try {
      await CapacitorAudioEngine.initPlaylist({
        tracks: playlist,
        preloadNext: true
      });
      setIsInitialized(true);
      updatePlaybackInfo();
    } catch (error) {
      console.error('Failed to initialize playlist:', error);
    }
  };

  const setupEventListeners = () => {
    CapacitorAudioEngine.addListener('trackChanged', () => {
      updatePlaybackInfo();
    });

    CapacitorAudioEngine.addListener('playbackStarted', () => {
      updatePlaybackInfo();
    });

    CapacitorAudioEngine.addListener('playbackPaused', () => {
      updatePlaybackInfo();
    });
  };

  const updatePlaybackInfo = async () => {
    try {
      const info = await CapacitorAudioEngine.getPlaybackInfo();
      setPlaybackInfo(info);
    } catch (error) {
      console.error('Failed to get playback info:', error);
    }
  };

  const handlePlay = async () => {
    try {
      await CapacitorAudioEngine.playAudio();
      updatePlaybackInfo();
    } catch (error) {
      console.error('Failed to play:', error);
    }
  };

  const handlePause = async () => {
    try {
      await CapacitorAudioEngine.pauseAudio();
      updatePlaybackInfo();
    } catch (error) {
      console.error('Failed to pause:', error);
    }
  };

  const handleNext = async () => {
    try {
      await CapacitorAudioEngine.skipToNext();
      updatePlaybackInfo();
    } catch (error) {
      console.error('Failed to skip to next:', error);
    }
  };

  const handlePrevious = async () => {
    try {
      await CapacitorAudioEngine.skipToPrevious();
      updatePlaybackInfo();
    } catch (error) {
      console.error('Failed to skip to previous:', error);
    }
  };

  if (!isInitialized) {
    return <div>Initializing playlist...</div>;
  }

  return (
    <div className="audio-player">
      <div className="track-info">
        <h3>{playbackInfo?.currentTrack?.title || 'No track'}</h3>
        <p>{playbackInfo?.currentTrack?.artist || 'Unknown artist'}</p>
        <p>
          {Math.floor(playbackInfo?.currentPosition || 0)}s /
          {Math.floor(playbackInfo?.duration || 0)}s
        </p>
      </div>

      <div className="controls">
        <button onClick={handlePrevious}>⏮️</button>
        <button onClick={playbackInfo?.isPlaying ? handlePause : handlePlay}>
          {playbackInfo?.isPlaying ? '⏸️' : '▶️'}
        </button>
        <button onClick={handleNext}>⏭️</button>
      </div>

      <div className="playlist">
        <h4>Playlist ({playbackInfo?.currentIndex + 1 || 0}/{playlist.length})</h4>
        {playlist.map((track, index) => (
          <div
            key={track.id}
            className={index === playbackInfo?.currentIndex ? 'current' : ''}
            onClick={() => CapacitorAudioEngine.skipToIndex({ index })}
          >
            {track.title} - {track.artist}
          </div>
        ))}
      </div>
    </div>
  );
};

export default AudioPlayer;
```

## Platform-Specific Features

### iOS

- Uses `AVQueuePlayer` for optimal playlist management
- Integrates with Lock Screen controls (`MPNowPlayingInfoCenter`)
- Supports background audio playback
- Handles audio interruptions automatically

### Android

- Uses `ExoPlayer` with `ConcatenatingMediaSource` for seamless playlist playback
- Integrates with `MediaSession` for notification controls
- Handles audio focus changes automatically
- Supports background playback with foreground service

### Web

- Uses HTML5 Audio API
- Basic playlist functionality
- No background playback support (browser limitation)

## Error Handling

```typescript
try {
  await CapacitorAudioEngine.playAudio();
} catch (error) {
  console.error('Playback failed:', error);
  // Handle error appropriately
}

// Listen for errors
CapacitorAudioEngine.addListener('playbackError', (event) => {
  console.error('Playback error:', event.message);
  // Show user-friendly error message
});
```
