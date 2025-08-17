# Capacitor Audio Engine 🎙️

Hey there! 👋 Welcome to the Native Audio plugin for Capacitor. This plugin makes it super easy to add high-quality audio recording to your mobile apps. Whether you're building a voice memo app, a podcast recorder, or just need to capture some audio, we've got you covered!

## 📑 Table of Contents

- [Capacitor Audio Engine 🎙️](#capacitor-audio-engine-️)
  - [📑 Table of Contents](#-table-of-contents)
  - [✨ Features](#-features)
  - [📱 Platform Support](#-platform-support)
  - [🚀 Installation](#-installation)
    - [Prerequisites](#prerequisites)
    - [Setup](#setup)
      - [iOS](#ios)
      - [Android](#android)
  - [📖 API Documentation](#-api-documentation)
    - [Core Interfaces](#core-interfaces)
    - [Methods](#methods)
      - [Permission Management](#permission-management)
      - [Recording Control](#recording-control)
      - [Status & Information](#status--information)
      - [Audio Processing](#audio-processing)
      - [Microphone Management](#microphone-management)
      - [Audio Playback](#audio-playback)
      - [Event Handling](#event-handling)
      - [Usage Example](#usage-example)
  - [🔧 Troubleshooting](#-troubleshooting)
    - [Common Issues](#common-issues)
  - [🛠️ Technical Details](#️-technical-details)
    - [Platform-Specific Implementations](#platform-specific-implementations)
      - [Web](#web)
      - [Android](#android-1)
      - [iOS](#ios-1)
  - [📚 Additional Documentation](#-additional-documentation)
  - [🤝 Contributing](#-contributing)
  - [📄 License](#-license)
  - [📞 Need Help?](#-need-help)

## ✨ Features

### 🎙️ Audio Recording

- 🎯 Record high-quality audio on Android and iOS
- ⏯️ Pause and resume your recordings
- 📊 Monitor recording status in real-time
- 🔒 Handle permissions automatically
- ✂️ Trim your audio files
- 📝 Get detailed recording metadata
- 🎙️ **Microphone management** - Detect and switch between available microphones
- 🔍 **Microphone status** - Check if microphone is busy/in use by other apps
- 📊 **Real-time waveform data** - Get amplitude levels during recording for UI visualizations
- ⚙️ **Enum-based configuration** - Type-safe recording options with quality presets
- 🎚️ **Quality presets** - Low/Medium/High presets for common use cases
- 🔧 **Custom settings** - Fine-grained control with sample rate, bitrate, and channel options

### 🎵 Audio Playback

- 📂 **Playlist support** - Initialize and manage playlists of multiple audio tracks
- ▶️ **Full playback controls** - Play, pause, resume, stop with seamless track transitions
- ⏭️ **Navigation** - Skip to next/previous track or jump to specific track index
- 🎯 **Seeking** - Seek to any position within the current track
- 📊 **Real-time info** - Get current track, position, duration, and playback status
- 🔔 **Event notifications** - Track changes, playback state, and error handling
- 🔄 **Auto-advance** - Automatically play next track when current track ends
- 📱 **Lock screen controls** - Native media controls on iOS and Android
- 🔊 **Background playback** - Continue playing when app is backgrounded
- 🎨 **Metadata support** - Display track title, artist, and artwork
- ⚡ **Audio preloading** - Preload audio files for faster playback start times
- 🎼 **Multi-audio resume** - Resume any of multiple audio files with custom settings
- 📋 **Audio information** - Get detailed metadata from local and remote audio files
- 📡 **Real-time monitoring** - Track playback progress and status changes
- 🌐 Cross-platform support (Web coming soon!)
- 🎚️ Consistent audio quality:
  - Default: Medium preset (22.05kHz, 64kbps, mono) - optimized for smaller files
  - Low preset: 16kHz, 32kbps, mono - perfect for voice notes
  - High preset: 44.1kHz, 128kbps, mono - excellent quality for music
  - Custom values: Full control over sample rate, bitrate, and channels

## 📱 Platform Support

| Feature              | Android | iOS | Web |
| -------------------- | ------- | --- | --- |
| Recording            | ✅      | ✅  | 🔜  |
| Pause/Resume         | ✅      | ✅  | 🔜  |
| Permission Handling  | ✅      | ✅  | 🔜  |
| Status Monitoring    | ✅      | ✅  | 🔜  |
| Audio Trimming       | ✅      | ✅  | 🔜  |
| Microphone Detection | ✅      | ✅  | 🔜  |
| Microphone Switching | ✅      | ✅  | 🔜  |
| Waveform Data        | ✅      | ✅  | ❌  |
| Audio Playback       | ✅      | ✅  | 🔜  |
| Playback Controls    | ✅      | ✅  | 🔜  |
| Audio Preloading     | ✅      | ✅  | ❌  |
| Multi-Audio Resume   | ✅      | ✅  | ❌  |
| Audio Information    | ✅      | ✅  | 🔜  |

> 💡 **Note:** Android and iOS are fully supported! Web support is coming soon - we're working on it! 🚧

## 🚀 Installation

### Prerequisites

- Node.js 14+ and npm
- Capacitor 5.0.0+
- iOS 13+ for iOS development
- Android 10+ (API level 29) for Android development

### Setup

1. Install the plugin:

NPM:

```bash
npm i capacitor-audio-engine
```

PNPM:

```bash
pnpm add capacitor-audio-engine
```

YARN

```bash
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

```typescript
export interface AudioFileInfo {
  path: string;
  webPath: string;
  uri: string;
  mimeType: string;
  size: number;
  duration: number;
  sampleRate: number;
  channels: number;
  bitrate: number;
  createdAt: number;
  filename: string;
  /**
   * Base64-encoded audio data with MIME prefix (Data URI format)
   * Format: "data:audio/m4a;base64,<base64-data>"
   */
  base64?: string;
}
```

#### `RecordingOptions`

```typescript
export interface RecordingOptions {
  /**
   * Audio sample rate (Hz). Default: AudioSampleRate.STANDARD_22K (optimized for smaller file sizes)
   */
  sampleRate?: AudioSampleRate | number;
  /**
   * Number of audio channels. Default: AudioChannels.MONO
   */
  channels?: AudioChannels | number;
  /**
   * Audio bitrate (bps). Default: AudioBitrate.MEDIUM (optimized for smaller file sizes)
   */
  bitrate?: AudioBitrate | number;
  /**
   * Audio quality preset. If specified, overrides individual sampleRate and bitrate settings.
   * - AudioQuality.LOW: 16kHz, 32kbps - smallest files, suitable for voice notes
   * - AudioQuality.MEDIUM: 22.05kHz, 64kbps - balanced quality/size (default)
   * - AudioQuality.HIGH: 44.1kHz, 128kbps - higher quality, larger files
   */
  quality?: AudioQuality;
  /**
   * Maximum recording duration in seconds.
   * When set, enables segment rolling mode:
   * - Records in 30-second segments
   * - Maintains rolling buffer of last 10 minutes (20 segments)
   * - Automatically merges segments when recording stops
   * If not set, uses linear recording mode.
   */
  maxDuration?: number;
  /**
   * Note: The audio format is always .m4a (MPEG-4/AAC) on all platforms.
   *
   * Enhanced Recording Features:
   * - Automatic segment rolling (30-second segments) for improved reliability
   * - Rolling window retention (10 minutes max) for efficient memory usage
   * - Automatic segment merging when recording stops
   * - Better handling of long recording sessions and interruptions
   */
}
```

#### Recording Configuration Enums

```typescript
export enum AudioSampleRate {
  /** Low quality - 8kHz for voice recording */
  VOICE_8K = 8000,
  /** Voice quality - 16kHz for speech */
  VOICE_16K = 16000,
  /** Standard quality - 22.05kHz (default optimized) */
  STANDARD_22K = 22050,
  /** CD quality - 44.1kHz */
  CD_44K = 44100,
  /** High quality - 48kHz */
  HIGH_48K = 48000,
}

export enum AudioChannels {
  /** Mono - single channel */
  MONO = 1,
  /** Stereo - two channels */
  STEREO = 2,
}

export enum AudioBitrate {
  /** Very low bitrate - 16kbps for voice notes */
  VERY_LOW = 16000,
  /** Low bitrate - 32kbps for voice recording */
  LOW = 32000,
  /** Medium bitrate - 64kbps (default optimized) */
  MEDIUM = 64000,
  /** High bitrate - 128kbps for music */
  HIGH = 128000,
  /** Very high bitrate - 256kbps for high quality */
  VERY_HIGH = 256000,
}

export enum AudioQuality {
  /** Low quality: 16kHz, 32kbps - smallest files, suitable for voice notes */
  LOW = 'low',
  /** Medium quality: 22.05kHz, 64kbps - balanced quality/size (default) */
  MEDIUM = 'medium',
  /** High quality: 44.1kHz, 128kbps - higher quality, larger files */
  HIGH = 'high',
}
```

#### `RecordingStatus`

```typescript
type RecordingStatus = 'idle' | 'recording' | 'paused';
```

#### `AudioRecordingEventName`

```typescript
type AudioRecordingEventName = 'durationChange' | 'error';
```

#### `DurationChangeData`

```typescript
export interface DurationChangeData {
  duration: number;
}
```

#### `ErrorEventData`

```typescript
export interface ErrorEventData {
  message: string;
  code?: string | number;
  details?: any;
}
```

#### `MicrophoneInfo`

```typescript
export interface MicrophoneInfo {
  id: number;
  name: string;
  type: 'internal' | 'external' | 'unknown';
  description?: string;
  uid?: string; // iOS only
  isConnected?: boolean; // Android only
}
```

#### `MicrophoneStatusResult`

```typescript
export interface MicrophoneStatusResult {
  busy: boolean;
  reason?: string;
}
```

#### `AvailableMicrophonesResult`

```typescript
export interface AvailableMicrophonesResult {
  microphones: MicrophoneInfo[];
}
```

#### `SwitchMicrophoneOptions`

```typescript
export interface SwitchMicrophoneOptions {
  microphoneId: number;
}
```

#### `SwitchMicrophoneResult`

```typescript
export interface SwitchMicrophoneResult {
  success: boolean;
  microphoneId: number;
}
```

#### `PlaybackStatus`

```typescript
type PlaybackStatus = 'idle' | 'loaded' | 'playing' | 'paused' | 'stopped' | 'completed' | 'error';
```

#### `PlaybackOptions`

```typescript
export interface PlaybackOptions {
  /**
   * Playback speed (0.5 - 2.0). Default: 1.0
   */
  speed?: number;
  /**
   * Start time in seconds. Default: 0
   */
  startTime?: number;
  /**
   * Whether to loop the audio. Default: false
   */
  loop?: boolean;
  /**
   * Volume level (0.0 - 1.0). Default: 1.0
   */
  volume?: number;
}
```

#### `ResumePlaybackOptions`

```typescript
export interface ResumePlaybackOptions {
  /**
   * URI of the audio file to resume
   * If not provided, resumes the currently paused playback
   */
  uri?: string;
  /**
   * Playback speed (0.5 - 2.0). Default: 1.0
   */
  speed?: number;
  /**
   * Volume level (0.0 - 1.0). Default: 1.0
   */
  volume?: number;
  /**
   * Whether to loop the audio. Default: false
   */
  loop?: boolean;
}
```

#### `PreloadOptions`

```typescript
export interface PreloadOptions {
  /**
   * URI of the audio file to preload
   */
  uri: string;
  /**
   * Whether to prepare for playback immediately. Default: true
   */
  prepare?: boolean;
}
```

#### `AudioPlayerInfo`

```typescript
export interface AudioPlayerInfo {
  status: PlaybackStatus;
  currentTime: number;
  duration: number;
  speed?: number;
  volume?: number;
  isLooping?: boolean;
  uri?: string;
}
```

#### `AudioPlaybackEventName`

```typescript
type AudioPlaybackEventName = 'playbackStatusChange' | 'playbackProgress' | 'playbackCompleted' | 'playbackError';
```

#### `PlaybackProgressData`

```typescript
export interface PlaybackProgressData {
  currentTime: number;
  duration: number;
  position: number; // Playback position as percentage (0-100)
}
```

#### `PlaybackStatusData`

```typescript
export interface PlaybackStatusData {
  status: PlaybackStatus;
  currentTime?: number;
  duration?: number;
}
```

#### `PlaybackErrorData`

```typescript
export interface PlaybackErrorData {
  message: string;
  code?: string | number;
  details?: any;
}
```

#### `PlaybackCompletedData`

```typescript
export interface PlaybackCompletedData {
  duration: number;
}
```

#### `GetAudioInfoOptions`

```typescript
export interface GetAudioInfoOptions {
  /**
   * URI of the audio file to analyze
   * Supports:
   * - Local file URIs (from stopRecording)
   * - Remote CDN URLs (HTTP/HTTPS)
   */
  uri: string;
}
```

### Methods

#### Permission Management

##### `checkPermission()`

Check if your app has permission to use the microphone.

```typescript
checkPermission(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;
```

##### `requestPermission()`

Ask the user for microphone permission.

```typescript
requestPermission(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;
```

#### Recording Control

##### `startRecording()`

Start recording audio from the device's microphone.

```typescript
startRecording(options?: RecordingOptions): Promise<void>;
```

##### `pauseRecording()`

Pause the current recording.

```typescript
pauseRecording(): Promise<void>;
```

##### `resumeRecording()`

Resume the current recording if it was previously paused.

```typescript
resumeRecording(): Promise<void>;
```

##### `stopRecording()`

Stop the current recording and get the recorded file information.

```typescript
stopRecording(): Promise<AudioFileInfo>;
```

#### Status & Information

##### `getDuration()`

Get the current recording duration.

```typescript
getDuration(): Promise<{ duration: number }>;
```

##### `getStatus()`

Check the current recording status.

```typescript
getStatus(): Promise<{ status: RecordingStatus; isRecording: boolean }>;
```

#### Audio Processing

##### `trimAudio()`

Trim an audio file to a specific duration.

```typescript
trimAudio(options: { uri: string; start: number; end: number }): Promise<AudioFileInfo>;
```

##### `getAudioInfo()`

Get detailed information about an audio file (local or remote).

```typescript
getAudioInfo(options: GetAudioInfoOptions): Promise<AudioFileInfo>;
```

**Example:**

```typescript
// Get info for a local recording
const localInfo = await CapacitorAudioEngine.getAudioInfo({
  uri: 'file:///path/to/recording.m4a',
});

// Get info for a remote CDN file
const remoteInfo = await CapacitorAudioEngine.getAudioInfo({
  uri: 'https://example.com/audio/sample.mp3',
});

console.log('Duration:', localInfo.duration, 'seconds');
console.log('File size:', localInfo.size, 'bytes');
console.log('Sample rate:', localInfo.sampleRate, 'Hz');
```

**Platform Notes:**

- **Android**: Uses MediaMetadataRetriever to extract metadata from local and remote files
- **iOS**: Uses AVAsset to extract metadata from local and remote files
- **Web**: Not supported

#### Microphone Management

##### `isMicrophoneBusy()`

Check if the microphone is currently being used by another application.

```typescript
isMicrophoneBusy(): Promise<MicrophoneStatusResult>;
```

**Example:**

```typescript
const status = await CapacitorAudioEngine.isMicrophoneBusy();
if (status.busy) {
  console.log('Microphone is busy:', status.reason);
} else {
  console.log('Microphone is available');
}
```

##### `getAvailableMicrophones()`

Get a list of available microphones (internal and external).

```typescript
getAvailableMicrophones(): Promise<AvailableMicrophonesResult>;
```

**Example:**

```typescript
const result = await CapacitorAudioEngine.getAvailableMicrophones();
result.microphones.forEach((mic) => {
  console.log(`${mic.name} (${mic.type}): ${mic.isConnected ? 'Connected' : 'Disconnected'}`);
});
```

##### `switchMicrophone()`

Switch to a different microphone while keeping recording active.

```typescript
switchMicrophone(options: SwitchMicrophoneOptions): Promise<SwitchMicrophoneResult>;
```

**Example:**

```typescript
// Get available microphones
const result = await CapacitorAudioEngine.getAvailableMicrophones();
const externalMic = result.microphones.find((mic) => mic.type === 'external');

if (externalMic) {
  try {
    const switchResult = await CapacitorAudioEngine.switchMicrophone({
      microphoneId: externalMic.id,
    });
    console.log('Switched to:', switchResult.message);
  } catch (error) {
    console.error('Failed to switch microphone:', error);
  }
}
```

**Platform Notes:**

- **Android**: Shows primary built-in microphone + all external devices (headsets, USB, Bluetooth)
- **iOS**: Shows all available audio inputs from AVAudioSession
- **Web**: Not supported (returns empty array)

#### Audio Playback

The Audio Engine now supports comprehensive playlist-based audio playback with full control over multiple tracks.

##### `preloadTracks()`

Preload audio tracks from URLs and initialize playlist for optimal performance.

```typescript
preloadTracks(options: PreloadTracksOptions): Promise<void>;

interface PreloadTracksOptions {
  tracks: string[]; // Array of audio URLs
  preloadNext?: boolean; // Default: true
}
```

**Example:**

```typescript
const trackUrls = ['https://example.com/song1.mp3', 'file:///path/to/local/song2.m4a', 'https://example.com/song3.mp3'];

await CapacitorAudioEngine.preloadTracks({
  tracks: trackUrls,
  preloadNext: true,
});
```

##### `playAudio()`

Start playback of the current track in the playlist.

```typescript
playAudio(): Promise<void>;
```

**Example:**

```typescript
await CapacitorAudioEngine.playAudio();
```

##### `pauseAudio()`

Pause the current audio playback.

```typescript
pauseAudio(): Promise<void>;
```

##### `resumeAudio()`

Resume paused audio playback.

```typescript
resumeAudio(): Promise<void>;
```

##### `stopAudio()`

Stop audio playback and reset to the beginning of the current track.

```typescript
stopAudio(): Promise<void>;
```

##### `seekAudio()`

Seek to a specific position within the current track.

```typescript
seekAudio(options: SeekOptions): Promise<void>;

interface SeekOptions {
  seconds: number;
}
```

**Example:**

```typescript
// Seek to 30 seconds
await CapacitorAudioEngine.seekAudio({ seconds: 30 });
```

##### `skipToNext()`

Skip to the next track in the playlist.

```typescript
skipToNext(): Promise<void>;
```

##### `skipToPrevious()`

Skip to the previous track in the playlist.

```typescript
skipToPrevious(): Promise<void>;
```

##### `skipToIndex()`

Jump to a specific track in the playlist by index.

```typescript
skipToIndex(options: SkipToIndexOptions): Promise<void>;

interface SkipToIndexOptions {
  index: number; // Zero-based track index
}
```

**Example:**

```typescript
// Jump to the third track (index 2)
await CapacitorAudioEngine.skipToIndex({ index: 2 });
```

##### `getPlaybackInfo()`

Get comprehensive information about the current playback state.

```typescript
getPlaybackInfo(): Promise<PlaybackInfo>;

interface PlaybackInfo {
  currentTrack: AudioTrack | null;
  currentIndex: number;
  currentPosition: number; // in seconds
  duration: number; // in seconds
  isPlaying: boolean;
  status: PlaybackStatus; // 'idle' | 'loading' | 'playing' | 'paused' | 'stopped'
}
```

**Example:**

```typescript
const info = await CapacitorAudioEngine.getPlaybackInfo();
console.log('Current track:', info.currentTrack?.title);
console.log('Position:', `${info.currentPosition}s / ${info.duration}s`);
console.log('Playing:', info.isPlaying);
console.log('Track index:', info.currentIndex);
```

#### Event Handling

##### `addListener()`

Add a listener for recording or playback events.

```typescript
addListener<T extends AudioEventName>(
  eventName: T,
  callback: (event: AudioEventMap[T]) => void,
): Promise<PluginListenerHandle>;
```

**Recording Event Examples:**

```typescript
// Listen for recording duration changes
await CapacitorAudioEngine.addListener('durationChange', (event) => {
  console.log('Recording duration:', event.duration, 'seconds');
});

// Listen for recording errors
await CapacitorAudioEngine.addListener('error', (event) => {
  console.error('Recording error:', event.message);
});
```

**Playback Event Examples:**

```typescript
// Listen for track changes
await CapacitorAudioEngine.addListener('trackChanged', (event) => {
  console.log('Track changed:', event.track.title, 'at index', event.index);
  // Update UI to show new track info
});

// Listen for track completion
await CapacitorAudioEngine.addListener('trackEnded', (event) => {
  console.log('Track ended:', event.track.title);
  // Track will auto-advance to next if available
});

// Listen for playback start
await CapacitorAudioEngine.addListener('playbackStarted', (event) => {
  console.log('Playback started:', event.track.title);
  // Update play/pause button state
});

// Listen for playback pause
await CapacitorAudioEngine.addListener('playbackPaused', (event) => {
  console.log('Playback paused:', event.track.title, 'at', event.position, 'seconds');
  // Update play/pause button state
});

// Listen for playback errors
await CapacitorAudioEngine.addListener('playbackError', (event) => {
  console.error('Playback error:', event.message);
  // Show error message to user
});
await CapacitorAudioEngine.addListener('playbackCompleted', (data) => {
  console.log('Playback completed, duration:', data.duration);
});

// Listen for playback errors
await CapacitorAudioEngine.addListener('playbackError', (data) => {
  console.error('Playback error:', data.message);
});
```

##### `removeAllListeners()`

Remove all listeners for recording events.

```typescript
removeAllListeners(): Promise<void>;
```

> **Note:** The audio format is always `.m4a` (MPEG-4/AAC) on all platforms.

#### Usage Example

Here's a complete example of how to use the plugin with microphone management:

```typescript
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import { AudioSampleRate, AudioChannels, AudioBitrate, AudioQuality } from 'capacitor-audio-engine';

class AudioRecorder {
  private isRecording = false;
  private availableMicrophones: MicrophoneInfo[] = [];
  private selectedMicrophoneId: number | null = null;

  async initialize() {
    // Check and request permission
    const permission = await CapacitorAudioEngine.checkPermission();
    if (!permission.granted) {
      const result = await CapacitorAudioEngine.requestPermission();
      if (!result.granted) {
        throw new Error('Microphone permission denied');
      }
    }

    // Load available microphones
    await this.loadMicrophones();

    // Set up event listeners
    await this.setupEventListeners();
  }

  async loadMicrophones() {
    try {
      const result = await CapacitorAudioEngine.getAvailableMicrophones();
      this.availableMicrophones = result.microphones;

      // Select internal microphone by default
      const internalMic = result.microphones.find((mic) => mic.type === 'internal');
      if (internalMic) {
        this.selectedMicrophoneId = internalMic.id;
      }

      console.log('Available microphones:', result.microphones);
    } catch (error) {
      console.error('Failed to load microphones:', error);
    }
  }

  async startRecording() {
    try {
      // Check if microphone is busy
      const status = await CapacitorAudioEngine.isMicrophoneBusy();
      if (status.busy) {
        throw new Error(`Microphone is busy: ${status.reason}`);
      }

      // Switch to selected microphone if available
      if (this.selectedMicrophoneId) {
        await CapacitorAudioEngine.switchMicrophone({
          microphoneId: this.selectedMicrophoneId,
        });
      }

      // Start recording with enum-based configuration (recommended)
      await CapacitorAudioEngine.startRecording({
        sampleRate: AudioSampleRate.CD_44K,
        channels: AudioChannels.MONO,
        bitrate: AudioBitrate.HIGH,
      });

      // Or use quality presets for quick configuration
      await CapacitorAudioEngine.startRecording({
        quality: AudioQuality.HIGH, // Automatically sets 44.1kHz, 128kbps
      });

      // Or use custom values (still supported)
      await CapacitorAudioEngine.startRecording({
        sampleRate: 32000, // Custom sample rate
        channels: 1, // Custom channel count
        bitrate: 96000, // Custom bitrate
      });

      this.isRecording = true;
      console.log('Recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async stopRecording() {
    try {
      const result = await CapacitorAudioEngine.stopRecording();
      this.isRecording = false;
      console.log('Recording saved:', result);
      return result;
    } catch (error) {
      console.error('Failed to stop recording:', error);
    }
  }

  async playRecording(audioFile: AudioFileInfo) {
    try {
      // Preload the audio file for better performance
      await CapacitorAudioEngine.preload({
        uri: audioFile.uri,
        prepare: true,
      });

      // Start playback with custom options
      await CapacitorAudioEngine.startPlayback({
        uri: audioFile.uri,
        speed: 1.0, // Normal speed
        volume: 1.0, // Full volume
        loop: false, // Don't loop
        startTime: 0, // Start from beginning
      });

      console.log('Playback started');
    } catch (error) {
      console.error('Failed to start playback:', error);
    }
  }

  async pausePlayback() {
    try {
      await CapacitorAudioEngine.pausePlayback();
      console.log('Playback paused');
    } catch (error) {
      console.error('Failed to pause playback:', error);
    }
  }

  async resumePlayback() {
    try {
      // Resume current playback (existing behavior)
      await CapacitorAudioEngine.resumePlayback();
      console.log('Playback resumed');
    } catch (error) {
      console.error('Failed to resume playback:', error);
    }
  }

  async resumePlaybackWithOptions(uri?: string, speed?: number, volume?: number, loop?: boolean) {
    try {
      // Resume with custom options - can switch to different audio files
      await CapacitorAudioEngine.resumePlayback({
        uri, // Optional: switch to different audio file
        speed, // Optional: custom playback speed
        volume, // Optional: custom volume level
        loop, // Optional: enable/disable looping
      });
      console.log('Playback resumed with custom options');
    } catch (error) {
      console.error('Failed to resume playback with options:', error);
    }
  }

  async stopPlayback() {
    try {
      await CapacitorAudioEngine.stopPlayback();
      console.log('Playback stopped');
    } catch (error) {
      console.error('Failed to stop playback:', error);
    }
  }

  async seekTo(time: number) {
    try {
      await CapacitorAudioEngine.seekTo({ time });
      console.log(`Seeked to ${time} seconds`);
    } catch (error) {
      console.error('Failed to seek:', error);
    }
  }

  async getPlaybackStatus() {
    try {
      const status = await CapacitorAudioEngine.getPlaybackStatus();
      console.log('Playback status:', status);
      return status;
    } catch (error) {
      console.error('Failed to get playback status:', error);
    }
  }

  async switchMicrophone(microphoneId: number) {
    try {
      const result = await CapacitorAudioEngine.switchMicrophone({ microphoneId });
      this.selectedMicrophoneId = result.microphoneId;
      console.log('Switched microphone:', result.message);
    } catch (error) {
      console.error('Failed to switch microphone:', error);
    }
  }

  private async setupEventListeners() {
    // Recording event listeners
    await CapacitorAudioEngine.addListener('durationChange', (data) => {
      console.log('Recording duration:', data.duration);
    });

    await CapacitorAudioEngine.addListener('error', (data) => {
      console.error('Recording error:', data.message);
    });

    // Playback event listeners
    await CapacitorAudioEngine.addListener('playbackProgress', (data) => {
      console.log(`Playback progress: ${data.currentTime}s / ${data.duration}s`);
    });

    await CapacitorAudioEngine.addListener('playbackStatusChange', (data) => {
      console.log('Playback status changed to:', data.status);
    });

    await CapacitorAudioEngine.addListener('playbackCompleted', (data) => {
      console.log('Playback completed, duration:', data.duration);
    });

    await CapacitorAudioEngine.addListener('playbackError', (data) => {
      console.error('Playback error:', data.message);
    });
  }

  async cleanup() {
    await CapacitorAudioEngine.removeAllListeners();
  }
}

// Usage
const recorder = new AudioRecorder();
await recorder.initialize();
```

### 📝 Recording Configuration Options

The plugin now supports enum-based configuration options for better type safety and developer experience. Here are all the ways you can configure recording options:

#### Using Quality Presets (Easiest)

Quality presets provide pre-configured settings optimized for common use cases:

```typescript
// Low quality - Perfect for voice notes (smallest file size)
await CapacitorAudioEngine.startRecording({
  quality: AudioQuality.LOW, // 16kHz, 32kbps, mono
});

// Medium quality - Balanced quality and file size (default)
await CapacitorAudioEngine.startRecording({
  quality: AudioQuality.MEDIUM, // 22.05kHz, 64kbps, mono
});

// High quality - Best quality for music recording
await CapacitorAudioEngine.startRecording({
  quality: AudioQuality.HIGH, // 44.1kHz, 128kbps, mono
});
```

#### Using Enums (Recommended for Custom Settings)

For more control while maintaining type safety:

```typescript
await CapacitorAudioEngine.startRecording({
  sampleRate: AudioSampleRate.CD_44K, // 44100 Hz
  channels: AudioChannels.STEREO, // 2 channels
  bitrate: AudioBitrate.VERY_HIGH, // 256000 bps
});

// Mix and match different quality levels
await CapacitorAudioEngine.startRecording({
  sampleRate: AudioSampleRate.VOICE_16K, // 16000 Hz - good for voice
  channels: AudioChannels.MONO, // 1 channel - saves space
  bitrate: AudioBitrate.MEDIUM, // 64000 bps - balanced
});
```

#### Available Enum Values

**Sample Rates (`AudioSampleRate`):**

- `VOICE_8K` = 8000 Hz - Basic voice recording
- `VOICE_16K` = 16000 Hz - Good voice quality
- `STANDARD_22K` = 22050 Hz - Standard quality (default)
- `CD_44K` = 44100 Hz - CD quality
- `HIGH_48K` = 48000 Hz - High quality

**Channels (`AudioChannels`):**

- `MONO` = 1 - Single channel (smaller files)
- `STEREO` = 2 - Two channels (stereo recording)

**Bitrates (`AudioBitrate`):**

- `VERY_LOW` = 16000 bps - Minimal quality
- `LOW` = 32000 bps - Voice recording
- `MEDIUM` = 64000 bps - Balanced (default)
- `HIGH` = 128000 bps - Good quality
- `VERY_HIGH` = 256000 bps - Excellent quality

#### Using Custom Values (Still Supported)

You can still use raw numeric values for complete customization:

```typescript
await CapacitorAudioEngine.startRecording({
  sampleRate: 32000, // Custom sample rate
  channels: 1, // Custom channel count
  bitrate: 96000, // Custom bitrate
  maxDuration: 300, // 5 minutes max
});
```

#### Advanced Features

**Segment Rolling Mode:**

```typescript
await CapacitorAudioEngine.startRecording({
  quality: AudioQuality.MEDIUM,
  maxDuration: 600, // Enable segment rolling for long recordings
});
```

When `maxDuration` is set:

- Records in 30-second segments
- Maintains a rolling buffer of the last 10 minutes
- Automatically merges segments when recording stops
- Improves reliability for long recording sessions

```
await recorder.startRecording();
// ... record audio ...
const audioFile = await recorder.stopRecording();
```

## 🔧 Troubleshooting

### Common Issues

1. **Permission Denied**
   - Ensure you've added the required permissions in your platform-specific files
   - Check if the user has granted permission in their device settings
   - Try requesting permission again
2. **Recording Not Starting**
   - Verify that you're not already recording
   - Check if the microphone is being used by another app
   - Ensure you have sufficient storage space
3. **Audio Quality Issues**
   - Check if the device's microphone is working properly
   - Verify that no other apps are using the microphone
   - Ensure you're not in a noisy environment
4. **File Access Issues**
   - Check if the app has proper storage permissions
   - Verify that the storage path is accessible
   - Ensure there's enough free space
5. **Microphone Issues**
   - Use `isMicrophoneBusy()` to check if another app is using the microphone
   - Try refreshing available microphones with `getAvailableMicrophones()`
   - Ensure external microphones (headsets, USB) are properly connected
   - On Android: Built-in microphone should always be available
   - On iOS: Check if microphone access is enabled in device settings
6. **Microphone Switching Issues**
   - Verify the microphone ID exists in the available microphones list
   - External microphones may disconnect during recording
   - Some devices may not support seamless microphone switching during recording

## 🛠️ Technical Details

### Platform-Specific Implementations

#### Web

- Uses MediaRecorder API
- Format: WebM container with Opus codec
- MIME Type: 'audio/webm;codecs=opus'
- Permission: Uses navigator.permissions.query API
- Audio trimming: Not supported (logs console message)
- **Microphone Management**: Not supported (returns empty arrays and placeholder responses)

#### Android

- Uses MediaRecorder
- Format: M4A container with AAC codec (MPEG-4/AAC, always .m4a)
- MIME Type: 'audio/m4a' or 'audio/m4a'
- Audio Source: MIC
- **Default Quality**: Medium preset (22.05kHz, 64kbps, mono) - optimized for smaller file sizes
- **Quality Presets**:
  - Low: 16kHz, 32kbps, mono (voice notes)
  - Medium: 22.05kHz, 64kbps, mono (balanced - default)
  - High: 44.1kHz, 128kbps, mono (high quality)
- Storage: App's external files directory under "Recordings" folder
- Filename Format: "recording\_[timestamp].m4a"
- **Background Recording**: Full support via foreground service with microphone type
- **Required Permission**: `android.permission.RECORD_AUDIO`
- **Background Permissions**: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`
- **Microphone Management**:
  - Uses AudioManager.getDevices() to enumerate input devices (API 23+)
  - Shows primary built-in microphone + all external devices
  - Supports headset, USB, and Bluetooth microphones
  - Uses AudioRecord for microphone busy detection
  - Microphone switching uses MediaRecorder.setPreferredDevice() (API 28+)

#### iOS

- Uses AVAudioRecorder
- Format: M4A container with AAC codec (MPEG-4/AAC, always .m4a)
- MIME Type: 'audio/m4a'
- **Default Quality**: Medium preset (22.05kHz, 64kbps, mono) - optimized for smaller file sizes
- **Quality Presets**:
  - Low: 16kHz, 32kbps, mono (voice notes)
  - Medium: 22.05kHz, 64kbps, mono (balanced - default)
  - High: 44.1kHz, 128kbps, mono (high quality)
- Uses AVAssetExportSession for audio trimming
- **Background Recording**: Supports continuous recording when app is backgrounded (requires 'audio' background mode)
- **Required Permission**: NSMicrophoneUsageDescription in Info.plist
- **Background Mode**: UIBackgroundModes with 'audio' capability
- **Microphone Management**:
  - Uses AVAudioSession.availableInputs to list audio inputs
  - Supports built-in, wired headset, and Bluetooth microphones
  - Uses AVAudioSession.setPreferredInput() for microphone switching
  - Real-time microphone busy detection via AVAudioSession

## 📚 Additional Documentation

For more detailed examples and advanced usage patterns, check out:

- **[Microphone Management Guide](docs/MICROPHONE_USAGE.md)** - Comprehensive guide for microphone detection, switching, and troubleshooting

## 🤝 Contributing

We love contributions! Whether it's fixing bugs, adding features, or improving docs, your help makes this plugin better for everyone. Here's how to help:

1. Fork the repo
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 📞 Need Help?

Found a bug? Have a feature request? Just want to chat? [Open an issue](https://github.com/abdelfattah-ashour/capacitor-native-audio/issues) on GitHub and we'll help you out!

---

Made with ❤️ by [Abdelfattah Ashour](https://github.com/abdelfattah-ashour)
