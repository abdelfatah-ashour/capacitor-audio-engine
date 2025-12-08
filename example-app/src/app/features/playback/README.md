# Playback Component

A dedicated page for demonstrating audio playback functionality using the CapacitorAudioEngine plugin.

## Overview

This component provides a complete implementation of:
- Track preloading and management
- Playback controls (play, pause, stop)
- Real-time progress tracking (250ms updates)
- Visual progress bars for each track
- Event-driven state management

## Features

### 🎵 **Track Management**
- Preload individual tracks or entire playlist
- Track preload status visualization
- Demo playlist with 3 sample tracks

### ▶️ **Playback Controls**
- Play/Pause/Stop for each track
- Real-time progress updates
- Seek functionality
- Visual feedback for playing state

### 📊 **Real-Time Updates**
- Progress updates every 250ms via `playbackProgress` event
- Live position and duration display
- Visual progress bars

### 🎯 **Event Handling**
- `playbackStarted` - Track starts playing
- `playbackPaused` - Track paused
- `playbackStopped` - Track stopped
- `playbackError` - Error handling
- `playbackProgress` - Real-time position updates

## Component Lifecycle

### Initialization (ngOnInit)
```typescript
async ngOnInit(): Promise<void> {
  console.log('🎵 Playback component initialized');
  
  // Setup all playback event listeners
  this.setupPlaybackEventListeners();
}
```

### Cleanup (ngOnDestroy)
```typescript
async ngOnDestroy(): Promise<void> {
  console.log('🧹 Playback component destroyed - cleaning up...');
  
  // 1. Destroy playback manager (releases native resources)
  await CapacitorAudioEngine.destroyPlayback();
  
  // 2. Remove all event listeners
  await CapacitorAudioEngine.removeAllListeners();
  
  console.log('✅ Playback cleanup complete');
}
```

## Key Methods

### Track Preloading

```typescript
// Preload a single track
async preloadTrack(url: string): Promise<void>

// Preload all tracks in the playlist
async preloadAllTracks(): Promise<void>
```

### Playback Control

```typescript
// Play a track (preloads if needed)
async playTrack(url: string): Promise<void>

// Pause a track
async pauseTrack(url: string): Promise<void>

// Stop a track
async stopTrack(url: string): Promise<void>

// Seek to a position
async seekTrack(url: string, seconds: number): Promise<void>
```

### State Management

```typescript
// Check if track is preloaded
isTrackPreloaded(url: string): boolean

// Check if track is playing
isTrackPlaying(url: string): boolean

// Get track position
getTrackPosition(url: string): number

// Get track duration
getTrackDuration(url: string): number

// Get track progress (0-1)
getTrackProgress(url: string): number
```

## Signal State

```typescript
// Track playback states for each URL
trackPlaybackStates = signal<Map<string, PlaybackInfo>>(new Map());

// Set of preloaded track URLs
preloadedTracks = signal<Set<string>>(new Set());

// Detailed info for preloaded tracks
preloadedTrackInfo = signal<PreloadedTrackInfo[]>([]);

// Whether real-time updates are active
realtimeUpdatesActive = signal(false);
```

## Demo Playlist

The component includes 3 demo tracks from Artlist:

1. **Continent** - Demo Track 1
2. **DuDa** - Demo Track 2
3. **EMEA** - Demo Track 3

All tracks are AAC format hosted on CDN for testing.

## Event Listeners

All playback events are setup in `setupPlaybackEventListeners()`:

```typescript
private setupPlaybackEventListeners(): void {
  // 1. playbackStarted - Shows toast when track starts
  // 2. playbackPaused - Updates track state
  // 3. playbackStopped - Updates track state
  // 4. playbackError - Shows error toast
  // 5. playbackProgress - Updates position/duration in real-time (250ms)
}
```

## Usage

### Accessing the Component

Navigate to: `/playback`

```typescript
// In your app
this.router.navigate(['/playback']);
```

### Standalone Usage

```typescript
import { PlaybackComponent } from './features/playback/playback.component';

// Use in routing
{
  path: 'playback',
  component: PlaybackComponent
}
```

## UI Structure

```
┌────────────────────────────────────────┐
│  Audio Playback Manager                │
│  Status, Preloaded count, Now playing  │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│  Preload Controls                      │
│  [Preload All] [Refresh States]        │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│  Demo Playlist                         │
│  ┌──────────────────────────────────┐  │
│  │ Track 1                          │  │
│  │ ▶️ Playing 1:23 / 3:45           │  │
│  │ [Progress Bar]                   │  │
│  │ [Pause] [Stop]                   │  │
│  └──────────────────────────────────┘  │
│  ┌──────────────────────────────────┐  │
│  │ Track 2                          │  │
│  │ ⏸️ Paused 0:45 / 4:12            │  │
│  │ [Progress Bar]                   │  │
│  │ [Play] [Stop]                    │  │
│  └──────────────────────────────────┘  │
│  ┌──────────────────────────────────┐  │
│  │ Track 3                          │  │
│  │ ⭕ Not preloaded                 │  │
│  │ [Load]                           │  │
│  └──────────────────────────────────┘  │
└────────────────────────────────────────┘

┌────────────────────────────────────────┐
│  Technical Information                 │
│  - Preloaded tracks count              │
│  - Real-time update status             │
│  - Event listeners active              │
│  - Track details (duration, size)      │
└────────────────────────────────────────┘
```

## File Structure

```
example-app/src/app/features/playback/
├── playback.component.ts       # Component logic
├── playback.component.html     # Template
├── playback.component.scss     # Styles
└── README.md                   # This file
```

## Integration with Native

### Android
- Uses `PlaybackManager` class
- MediaPlayer instances for each track
- Audio focus management
- Real-time progress callback (250ms)

### iOS
- Will use AVPlayer instances
- Similar playback management
- Real-time progress updates

### Web
- Uses HTML5 Audio API
- Browser-native playback

## Differences from Features Demo

The playback component is **focused and dedicated**:
- ✅ Only playback functionality
- ✅ Clean, single-purpose component
- ✅ Proper lifecycle management with `destroyPlayback()`
- ✅ Independent from recording/waveform features
- ✅ Easier to test and maintain

## Testing

### Manual Testing Steps

1. Navigate to `/playback`
2. Click "Preload All Tracks" - Should show success toast
3. Click Play on any track - Should start playback and show progress
4. Watch progress bar update in real-time (every 250ms)
5. Click Pause - Should pause and maintain position
6. Click Play again - Should resume from saved position
7. Click Stop - Should stop and reset to beginning
8. Navigate away - Should clean up resources
9. Return to `/playback` - Should work perfectly again

### Automated Testing

```typescript
describe('PlaybackComponent', () => {
  it('should preload tracks', async () => {
    await component.preloadAllTracks();
    expect(component.preloadedTracksCount()).toBe(3);
  });

  it('should play track', async () => {
    await component.playTrack(demoUrl);
    expect(component.isTrackPlaying(demoUrl)).toBe(true);
  });

  it('should cleanup on destroy', async () => {
    await component.ngOnDestroy();
    // Verify destroyPlayback and removeAllListeners were called
  });
});
```

## Console Output

```
🎵 Playback component initialized
🎵 Setting up playback event listeners (controlled by active flag)...
✅ Playback listeners setup complete (5 listeners) - controlled by active flag

[User preloads tracks]
Preloaded: Continent (audio/aac)

[User plays track]
Now playing: Continent

[User navigates away]
🧹 Playback component destroyed - cleaning up...
D/CapacitorAudioEngine: Destroying playback manager...
D/PlaybackManager: PlaybackManager released
✅ Playback cleanup complete
```

## Best Practices

### ✅ DO
- Preload tracks before playing
- Handle errors gracefully with toasts
- Update UI based on real-time events
- Clean up in ngOnDestroy

### ❌ DON'T
- Play tracks without preloading
- Ignore playback errors
- Poll for progress (use events!)
- Forget to call destroyPlayback()

## Related Documentation

- [PLAYBACK_CLEANUP_METHOD.md](../../../PLAYBACK_CLEANUP_METHOD.md) - destroyPlayback() method details
- [PlaybackManager.java](../../../android/src/main/java/com/capacitor/audioengine/PlaybackManager.java) - Native implementation
- [definitions.ts](../../../src/definitions.ts) - TypeScript API definitions

