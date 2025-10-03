# Capacitor Audio Engine 🎙️

A powerful Capacitor plugin for audio recording and playback on mobile devices. This plugin provides high-quality audio recording with real-time monitoring and flexible playback controls for iOS and Android platforms.

> 💡 **Note:** This plugin is designed for native mobile platforms (Android and iOS). Web platform is not supported.

## 📋 Overview of Methods and Types

### Methods

| Method Name                                                     | Description                                             |
| --------------------------------------------------------------- | ------------------------------------------------------- |
| [`checkPermissions`](#checkpermissions)                         | Check all audio-related permissions.                    |
| [`checkPermissionMicrophone`](#checkpermissionmicrophone)       | Check microphone permission.                            |
| [`checkPermissionNotifications`](#checkpermissionnotifications) | Check notification permission.                          |
| [`requestPermissions`](#requestpermissionsoptions)              | Request permissions with optional configuration.        |
| [`openSettings`](#opensettings)                                 | Open device settings for manual permission management.  |
| [`startRecording`](#startrecordingoptions)                      | Start recording with specified output path.             |
| [`stopRecording`](#stoprecording)                               | Stop recording and get file information.                |
| [`pauseRecording`](#pauserecording)                             | Pause the current recording.                            |
| [`resumeRecording`](#resumerecording)                           | Resume a paused recording.                              |
| [`resetRecording`](#resetrecording)                             | Reset the current recording session without finalizing. |
| [`getRecordingStatus`](#getrecordingstatus)                     | Get current recording status.                           |
| [`preloadTracks`](#preloadtracksoptions)                        | Preload audio tracks for optimized playback.            |
| [`playAudio`](#playaudiooptions)                                | Play current track or specific track by URL.            |
| [`pauseAudio`](#pauseaudiooptions)                              | Pause current track or specific track.                  |
| [`resumeAudio`](#resumeaudiooptions)                            | Resume paused playback.                                 |
| [`stopAudio`](#stopaudiooptions)                                | Stop playback and reset to beginning.                   |
| [`seekAudio`](#seekaudiooptions)                                | Seek to specific position in track.                     |
| [`skipToNext`](#skiptonext)                                     | Skip to next track in playlist.                         |
| [`skipToPrevious`](#skiptoprevious)                             | Skip to previous track in playlist.                     |
| [`skipToIndex`](#skiptoindexoptions)                            | Jump to specific track by index.                        |
| [`getPlaybackInfo`](#getplaybackinfo)                           | Get current playback information.                       |
| [`configureWaveform`](#configurewaveformoptions)                | Configure real-time audio level monitoring.             |
| [`destroyWaveform`](#destroywaveform)                           | Clean up waveform resources.                            |
| [`trimAudio`](#trimaudiooptions)                                | Trim audio file to specific time range.                 |
| [`getAudioInfo`](#getaudioinfooptions)                          | Get detailed audio file information.                    |
| [`addListener`](#addlistenereventname-callback)                 | Listen for recording and playback events.               |
| [`removeAllListeners`](#removealllisteners)                     | Remove all event listeners.                             |

### Interfaces, Enums, and Types

| Name                                                            | Description                                     |
| --------------------------------------------------------------- | ----------------------------------------------- |
| [`AudioFileInfo`](#audiofileinfo)                               | Complete information about an audio file.       |
| [`PermissionStatusResults`](#permissionstatusresults)           | Simplified permission status.                   |
| [`PermissionStatus`](#permissionstatus)                         | Enum for detailed permission statuses.          |
| [`AudioPermissionType`](#audiopermissiontype)                   | Enum for audio permission types.                |
| [`RecordingStatusInfo`](#recordingstatusinfo)                   | Current recording state.                        |
| [`RecordingStatus`](#recordingstatus)                           | Type for recording statuses.                    |
| [`PlaybackInfo`](#playbackinfo)                                 | Current playback state.                         |
| [`PlaybackStatus`](#playbackstatus)                             | Type for playback statuses.                     |
| [`WaveLevelConfiguration`](#wavelevelconfiguration)             | Waveform monitoring configuration.              |
| [`WaveLevelEmissionInterval`](#wavelevelemissioninterval)       | Enum for waveform emission intervals.           |
| [`PermissionRequestOptions`](#permissionrequestoptions)         | Options for requesting permissions.             |
| [`PreloadTracksOptions`](#preloadtracksoptions)                 | Options for preloading audio tracks.            |
| [`PreloadTracksResult`](#preloadtracksresult)                   | Result of preloading tracks.                    |
| [`PreloadedTrackInfo`](#preloadedtrackinfo)                     | Information about preloaded tracks.             |
| [`PlayAudioOptions`](#playaudiooptions)                         | Options for playing audio.                      |
| [`PauseAudioOptions`](#pauseaudiooptions)                       | Options for pausing audio.                      |
| [`ResumeAudioOptions`](#resumeaudiooptions)                     | Options for resuming audio.                     |
| [`StopAudioOptions`](#stopaudiooptions)                         | Options for stopping audio.                     |
| [`SeekOptions`](#seekoptions)                                   | Options for seeking audio.                      |
| [`SkipToIndexOptions`](#skiptoindexoptions)                     | Options for skipping to a specific track index. |
| [`WaveLevelConfigurationResult`](#wavelevelconfigurationresult) | Result of waveform configuration.               |
| [`TrimAudioOptions`](#trimaudiooptions)                         | Options for trimming audio.                     |
| [`DurationChangeData`](#durationchangedata)                     | Event data for duration changes.                |
| [`WaveLevelData`](#waveleveldata)                               | Event data for waveform levels.                 |
| [`ErrorEventData`](#erroreventdata)                             | Event data for errors.                          |
| [`PermissionStatusChangedData`](#permissionstatuschangeddata)   | Event data for permission status changes.       |
| [`RecordingStatusChangedData`](#recordingstatuschangeddata)     | Event data for recording status changes.        |
| [`PlaybackStartedData`](#playbackstarteddata)                   | Event data for playback started.                |
| [`PlaybackPausedData`](#playbackpauseddata)                     | Event data for playback paused.                 |
| [`PlaybackStoppedData`](#playbackstoppeddata)                   | Event data for playback stopped.                |
| [`PlaybackErrorData`](#playbackerrordata)                       | Event data for playback errors.                 |
| [`PlaybackProgressData`](#playbackprogressdata)                 | Event data for playback progress.               |

---

## 🚀 Installation

### Prerequisites

- Capacitor 5.0.0+

### Setup

1. Install the plugin:

```bash
npm install capacitor-audio-engine
# or
pnpm add capacitor-audio-engine
# or
yarn add capacitor-audio-engine
```

2. Sync your project:

```bash
npx cap sync
```

3. Add required permissions:

#### iOS

Add these to your `Info.plist`:

```xml
<key>NSMicrophoneUsageDescription</key>
<string>We need access to your microphone to record audio</string>
```

#### Android

Add this to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

## 📖 API Documentation

### Core Interfaces

#### `AudioFileInfo`

Complete information about an audio file:

```typescript
interface AudioFileInfo {
  path: string; // File system path
  webPath: string; // Web accessible path
  uri: string; // URI for file access
  mimeType: string; // MIME type (audio/m4a)
  size: number; // File size in bytes
  duration: number; // Duration in seconds
  sampleRate: number; // Sample rate in Hz
  channels: number; // Number of audio channels
  bitrate: number; // Bitrate in bps
  createdAt: number; // Creation timestamp
  filename: string; // File name
}
```

#### `PermissionStatusResults`

Simplified permission status:

```typescript
interface PermissionStatusResults {
  granted: boolean; // Overall permission status
  status: PermissionStatus; // Detailed status
}

enum PermissionStatus {
  GRANTED = 'granted',
  DENIED = 'denied',
  DENIED_PERMANENTLY = 'denied_permanently',
  NOT_DETERMINED = 'not_determined',
  LIMITED = 'limited',
  RESTRICTED = 'restricted',
  REQUESTING = 'requesting',
  UNSUPPORTED = 'unsupported',
}

enum AudioPermissionType {
  MICROPHONE = 'microphone',
  NOTIFICATIONS = 'notifications',
}
```

#### `RecordingStatusInfo`

Current recording state:

```typescript
interface RecordingStatusInfo {
  status: RecordingStatus;
  duration: number;
  path?: string;
}

type RecordingStatus = 'recording' | 'paused' | 'stopped' | 'idle';
```

#### `PlaybackInfo`

Current playback state:

```typescript
interface PlaybackInfo {
  currentTrack: {
    id: string;
    url: string;
  } | null;
  currentIndex: number;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}

type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped';
```

#### `WaveLevelConfiguration`

Waveform monitoring configuration:

```typescript
interface WaveLevelConfiguration {
  emissionInterval?: WaveLevelEmissionInterval | number;
}

enum WaveLevelEmissionInterval {
  REALTIME = 50, // 50ms - real-time
  VERY_FAST = 100, // 100ms - very fast
  FAST = 200, // 200ms - fast
  MEDIUM = 500, // 500ms - medium
  DEFAULT = 1000, // 1000ms - default
}
```

### Methods

#### Permission Management

##### `checkPermissions()`

Check all audio-related permissions:

```typescript
checkPermissions(): Promise<PermissionStatusResults>
```

##### `checkPermissionMicrophone()`

Check microphone permission:

```typescript
checkPermissionMicrophone(): Promise<PermissionStatusResults>
```

##### `checkPermissionNotifications()`

Check notification permission:

```typescript
checkPermissionNotifications(): Promise<PermissionStatusResults>
```

##### `requestPermissions(options?)`

Request permissions with optional configuration:

```typescript
requestPermissions(options?: PermissionRequestOptions): Promise<PermissionStatusResults>

interface PermissionRequestOptions {
  showRationale?: boolean;      // Show rationale before requesting (Android)
  rationaleMessage?: string;    // Custom rationale message
  forceRequest?: boolean;       // Force request even if denied permanently
}
```

##### `openSettings()`

Open device settings for manual permission management:

```typescript
openSettings(): Promise<void>
```

#### Recording Control

##### `startRecording(options)`

Start recording with specified output path:

```typescript
startRecording(options: { path: string }): Promise<void>
```

##### `stopRecording()`

Stop recording and get file information:

```typescript
stopRecording(): Promise<AudioFileInfo>
```

##### `pauseRecording()`

Pause the current recording:

```typescript
pauseRecording(): Promise<void>
```

##### `resumeRecording()`

Resume a paused recording:

```typescript
resumeRecording(): Promise<void>
```

##### `resetRecording()`

Reset the current recording session without finalizing:

```typescript
resetRecording(): Promise<void>
```

##### `getRecordingStatus()`

Get current recording status:

```typescript
getRecordingStatus(): Promise<RecordingStatusInfo>
```

#### Playback Control

##### `preloadTracks(options)`

Preload audio tracks for optimized playback:

```typescript
preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult>

interface PreloadTracksOptions {
  tracks: string[];  // Array of track URLs or file paths
}

interface PreloadTracksResult {
  tracks: PreloadedTrackInfo[];
}

interface PreloadedTrackInfo {
  url: string;
  loaded: boolean;
  mimeType?: string;
  duration?: number;
  size?: number;
}
```

##### `playAudio(options?)`

Play current track or specific track by URL:

```typescript
playAudio(options?: PlayAudioOptions): Promise<void>

interface PlayAudioOptions {
  url?: string;  // Optional URL to play specific track
}
```

##### `pauseAudio(options?)`

Pause current track or specific track:

```typescript
pauseAudio(options?: PauseAudioOptions): Promise<void>

interface PauseAudioOptions {
  url?: string;
}
```

##### `resumeAudio(options?)`

Resume paused playback:

```typescript
resumeAudio(options?: ResumeAudioOptions): Promise<void>

interface ResumeAudioOptions {
  url?: string;
}
```

##### `stopAudio(options?)`

Stop playback and reset to beginning:

```typescript
stopAudio(options?: StopAudioOptions): Promise<void>

interface StopAudioOptions {
  url?: string;
}
```

##### `seekAudio(options)`

Seek to specific position in track:

```typescript
seekAudio(options: SeekOptions): Promise<void>

interface SeekOptions {
  seconds: number;
  url?: string;
}
```

##### `skipToNext()`

Skip to next track in playlist:

```typescript
skipToNext(): Promise<void>
```

##### `skipToPrevious()`

Skip to previous track in playlist:

```typescript
skipToPrevious(): Promise<void>
```

##### `skipToIndex(options)`

Jump to specific track by index:

```typescript
skipToIndex(options: SkipToIndexOptions): Promise<void>

interface SkipToIndexOptions {
  index: number;
}
```

##### `getPlaybackInfo()`

Get current playback information:

```typescript
getPlaybackInfo(): Promise<PlaybackInfo>
```

#### Waveform Monitoring

##### `configureWaveform(options?)`

Configure real-time audio level monitoring:

```typescript
configureWaveform(options?: { EmissionInterval?: number }): Promise<WaveLevelConfigurationResult>

interface WaveLevelConfigurationResult {
  success: boolean;
  configuration: {
    emissionInterval: number;
  };
}
```

##### `destroyWaveform()`

Clean up waveform resources:

```typescript
destroyWaveform(): Promise<void>
```

#### Audio Processing

##### `trimAudio(options)`

Trim audio file to specific time range:

```typescript
trimAudio(options: TrimAudioOptions): Promise<AudioFileInfo>

interface TrimAudioOptions {
  uri: string;        // URI or file path
  startTime: number;  // Start time in seconds
  endTime: number;    // End time in seconds
}
```

##### `getAudioInfo(options)`

Get detailed audio file information:

```typescript
getAudioInfo(options: { uri: string }): Promise<AudioFileInfo>
```

#### Event Handling

##### `addListener(eventName, callback)`

Listen for recording and playback events:

```typescript
addListener<T extends AudioEventName>(
  eventName: T,
  callback: (event: AudioEventMap[T]) => void
): Promise<PluginListenerHandle>
```

**Recording Events:**

- `durationChange` - Duration updates during recording
- `error` - Recording errors
- `waveLevel` - Real-time audio level data (requires `configureWaveform`)
- `waveLevelInit` - Waveform initialization status
- `waveLevelDestroy` - Waveform cleanup status
- `waveLevelError` - Waveform errors
- `permissionStatusChanged` - Permission status changes
- `recordingStatusChanged` - Recording status changes

**Playback Events:**

- `playbackStarted` - Playback started
- `playbackPaused` - Playback paused
- `playbackStopped` - Playback stopped
- `playbackError` - Playback errors
- `playbackProgress` - Progress updates during playback

**Event Data Structures:**

```typescript
interface DurationChangeData {
  duration: number;
}

interface WaveLevelData {
  level: number; // Normalized 0.0-1.0
  timestamp: number;
}

interface ErrorEventData {
  message: string;
  code?: string | number;
  details?: any;
}

interface PermissionStatusChangedData {
  permissionType: AudioPermissionType;
  status: PermissionStatus;
  previousStatus?: PermissionStatus;
  message?: string;
}

interface RecordingStatusChangedData {
  status: RecordingStatus;
}

interface PlaybackStartedData {
  trackId: string;
  url: string;
}

interface PlaybackPausedData {
  trackId: string;
  url: string;
  position: number;
}

interface PlaybackStoppedData {
  trackId: string;
  url: string;
}

interface PlaybackErrorData {
  trackId: string;
  message: string;
}

interface PlaybackProgressData {
  trackId: string;
  url: string;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}
```

##### `removeAllListeners()`

Remove all event listeners:

```typescript
removeAllListeners(): Promise<void>
```

## 🛠️ Technical Details

### Platform-Specific Implementations

**Android:**

- Recording: MediaRecorder with AAC codec
- Playback: MediaPlayer for individual track management
- Format: M4A/AAC (audio/m4a)
- Sample Rate: 48kHz, Mono, 128kbps

**iOS:**

- Recording: AVAudioRecorder with AAC codec
- Playback: AVPlayer for individual track management
- Format: M4A/AAC (audio/m4a)
- Sample Rate: 48kHz, Mono, 128kbps

**Web:**

- Not supported - designed for native mobile platforms only

## 🤝 Contributing

We love contributions! Whether it's fixing bugs, adding features, or improving docs, your help makes this plugin better for everyone. Here's how to help:

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/feature-name`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/feature-name`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 📞 Need Help?

Found a bug? Have a feature request? Just want to chat? [Open an issue](https://github.com/abdelfattah-ashour/capacitor-native-audio/issues) on GitHub and we'll help you out!
