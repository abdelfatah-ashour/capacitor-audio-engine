# Capacitor Audio Engine 🎙️

Hey there! 👋 Welcome to the Native Audio plugin for Capacitor. This plugin makes it super easy to add both high-quality audio recording and powerful audio playback to your mobile apps. Whether you're building a voice memo app, a podcast player, a music app, or just need to record and play audio, we've got you covered!

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

- 🎯 **High-quality recording** on Android and iOS
- ⏯️ **Pause and resume** your recordings seamlessly
- 🔄 **Reset recordings** without stopping (keeps settings, starts fresh)
- 📊 **Real-time monitoring** with duration tracking and recording status
- 🔒 **Smart permission handling** with detailed status information
- ✂️ **Audio trimming** to cut your recordings to the perfect length
- 📝 **Rich metadata** including file info, duration, sample rate, and more
- 🎙️ **Microphone management** - detect and switch between available microphones
- 🔍 **Microphone status checking** - know if the mic is busy with other apps
- 📊 **Real-time waveform data** - get audio levels for stunning visualizations
- ⚙️ **Flexible configuration** with quality presets and custom settings
- 🔧 **Segment recording** - automatic multi-segment recording for long sessions

### 🎵 Audio Playback

- 📂 **Playlist management** - load and control multiple audio tracks
- ▶️ **Complete playback controls** - play, pause, resume, stop with smooth transitions
- ⏭️ **Track navigation** - skip to next/previous or jump to any track by index
- 🎯 **Precise seeking** - jump to any position within tracks
- 📊 **Real-time progress** - get current position, duration, and playback status
- 🔔 **Event notifications** - track changes, completion, errors, and more
- 🔄 **Auto-advance** - automatically move to the next track
- 📱 **Background support** - continue playing when app is backgrounded
- 🎨 **Rich metadata** - support for track titles, artists, and artwork
- ⚡ **Smart preloading** - preload tracks for instant playback

### 🔧 Cross-Platform Excellence

- 📱 **Native performance** on Android and iOS
- 🎵 **Consistent format** - M4A/AAC across all platforms
- 🎚️ **Quality control** - from voice notes (16kHz) to high-quality music (48kHz)
- 🔄 **Optimized defaults** - 22.05kHz, 64kbps, mono for smaller file sizes
- 📐 **Flexible options** - choose quality presets or fine-tune every setting

## 📱 Platform Support

| Feature                | Android | iOS | Web |
| ---------------------- | ------- | --- | --- |
| Audio Recording        | ✅      | ✅  | 🔜  |
| Pause/Resume Recording | ✅      | ✅  | 🔜  |
| Permission Management  | ✅      | ✅  | ❌  |
| Recording Status       | ✅      | ✅  | 🔜  |
| Audio Trimming         | ✅      | ✅  | ❌  |
| Microphone Detection   | ✅      | ✅  | ❌  |
| Microphone Switching   | ✅      | ✅  | ❌  |
| Waveform Data          | ✅      | ✅  | ❌  |
| Audio Playback         | ✅      | ✅  | 🔜  |
| Playlist Management    | ✅      | ✅  | 🔜  |
| Background Playback    | ✅      | ✅  | ❌  |

> 💡 **Note:** Android and iOS are fully supported with all features! Web support is in development for core recording and playback features. 🚧

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

Information about recorded or audio files:

```typescript
interface AudioFileInfo {
  path: string; // File system path
  webPath: string; // Web accessible path
  uri: string; // URI for file access
  mimeType: string; // MIME type (audio/m4a)
  size: number; // File size in bytes
  duration: number; // Duration in seconds
  sampleRate: number; // Audio sample rate (Hz)
  channels: number; // Number of audio channels
  bitrate: number; // Audio bitrate (bps)
  createdAt: number; // Creation timestamp
  filename: string; // File name
}
```

#### `RecordingOptions`

Configure your recording settings:

```typescript
interface RecordingOptions {
  sampleRate?: AudioSampleRate | number; // Default: 22050 Hz
  channels?: AudioChannels | number; // Default: 1 (mono)
  bitrate?: AudioBitrate | number; // Default: 64000 bps
  maxDuration?: number; // Max duration in seconds
}
```

#### Recording Configuration Enums

Type-safe options for recording quality:

```typescript
enum AudioSampleRate {
  VOICE_8K = 8000, // Basic voice recording
  VOICE_16K = 16000, // Good voice quality
  STANDARD_22K = 22050, // Standard quality (default)
  CD_44K = 44100, // CD quality
  HIGH_48K = 48000, // High quality
}

enum AudioChannels {
  MONO = 1, // Single channel (smaller files)
  STEREO = 2, // Two channels (stereo)
}

enum AudioBitrate {
  VERY_LOW = 16000, // Voice notes
  LOW = 32000, // Voice recording
  MEDIUM = 64000, // Balanced (default)
  HIGH = 128000, // Good quality
  VERY_HIGH = 256000, // Excellent quality
}
```

#### Permission Management

```typescript
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

interface PermissionStatusResults {
  granted: boolean; // permission status
  status: PermissionStatus; // status
}
```

#### Audio Playback

```typescript
interface AudioTrack {
  id: string;
  url: string;
  title?: string;
  artist?: string;
  artworkUrl?: string;
}

interface PlaybackInfo {
  currentTrack: AudioTrack | null;
  currentIndex: number;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
  status: PlaybackStatus;
}

type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped';
```

#### Microphone Management

```typescript
interface MicrophoneInfo {
  id: number;
  name: string;
  type: 'internal' | 'external' | 'unknown';
  description?: string;
  uid?: string; // iOS only
  isConnected?: boolean; // Android only
}
```

#### Waveform Configuration

```typescript
enum WaveLevelEmissionInterval {
  REALTIME = 50, // 50ms - real-time
  VERY_FAST = 100, // 100ms - very fast
  FAST = 200, // 200ms - fast
  MEDIUM = 500, // 500ms - medium
  DEFAULT = 1000, // 1000ms - default
}

interface WaveLevelConfiguration {
  emissionInterval?: WaveLevelEmissionInterval | number;
}
```

### Methods

#### Permission Management

##### `checkPermissions()`

Check the status of all audio-related permissions with detailed information:

```typescript
checkPermissions(): Promise<PermissionStatusResults>;
```

**Example:**

```typescript
const permissions = await CapacitorAudioEngine.checkPermissions();
console.log('Overall granted:', permissions.granted);
console.log('Microphone:', permissions.microphone.status);
console.log('Notifications:', permissions.notifications.status);
```

##### `checkPermissionMicrophone()`

Check only microphone permission status:

```typescript
checkPermissionMicrophone(): Promise<SinglePermissionStatus>;
```

##### `checkPermissionNotifications()`

Check only notification permission status:

```typescript
checkPermissionNotifications(): Promise<SinglePermissionStatus>;
```

##### `requestPermissions()`

Request permissions with detailed options and status:

```typescript
requestPermissions(options?: PermissionRequestOptions): Promise<PermissionStatusResults>;
```

##### `openSettings()`

Navigate to app settings for manual permission management:

```typescript
openSettings(): Promise<void>;
```

#### Recording Control

##### `startRecording()`

Start recording audio with optional configuration:

```typescript
startRecording(options?: RecordingOptions): Promise<void>;
```

**Example:**

```typescript
// Start with default settings (22.05kHz, 64kbps, mono)
await CapacitorAudioEngine.startRecording();

// Custom configuration
await CapacitorAudioEngine.startRecording({
  sampleRate: AudioSampleRate.CD_44K,
  channels: AudioChannels.STEREO,
  bitrate: AudioBitrate.HIGH,
  maxDuration: 300, // 5 minutes max
});
```

##### `pauseRecording()`

Pause the current recording:

```typescript
pauseRecording(): Promise<void>;
```

##### `resumeRecording()`

Resume a paused recording:

```typescript
resumeRecording(): Promise<void>;
```

##### `resetRecording()`

Reset the current recording session (keeps settings, discards data):

```typescript
resetRecording(): Promise<void>;
```

##### `stopRecording()`

Stop recording and get the audio file information:

```typescript
stopRecording(): Promise<AudioFileInfo>;
```

#### Status & Information

##### `getDuration()`

Get the current recording duration:

```typescript
getDuration(): Promise<{ duration: number }>;
```

##### `getStatus()`

Get comprehensive recording status:

```typescript
getStatus(): Promise<{
  status: RecordingStatus;
  isRecording: boolean;
  duration: number;
}>;
```

#### Audio Processing

##### `trimAudio()`

Trim an audio file to a specific duration:

```typescript
trimAudio(options: { uri: string; start: number; end: number }): Promise<AudioFileInfo>;
```

#### Microphone Management

##### `isMicrophoneBusy()`

Check if the microphone is being used by another app:

```typescript
isMicrophoneBusy(): Promise<MicrophoneStatusResult>;
```

##### `getAvailableMicrophones()`

Get a list of all available microphones:

```typescript
getAvailableMicrophones(): Promise<AvailableMicrophonesResult>;
```

##### `switchMicrophone()`

Switch to a different microphone during recording:

```typescript
switchMicrophone(options: SwitchMicrophoneOptions): Promise<SwitchMicrophoneResult>;
```

#### Waveform Configuration

##### `configureWaveform()`

Set up real-time audio level monitoring:

```typescript
configureWaveform(options?: { EmissionInterval?: number }): Promise<WaveLevelConfigurationResult>;
```

**Example:**

```typescript
// Configure with 200ms intervals
await CapacitorAudioEngine.configureWaveform({
  EmissionInterval: WaveLevelEmissionInterval.FAST,
});

// Listen for wave level events
await CapacitorAudioEngine.addListener('waveLevel', (data) => {
  console.log('Audio level:', data.level); // 0.0 to 1.0
});
```

##### `destroyWaveform()`

Clean up waveform configuration and resources:

```typescript
destroyWaveform(): Promise<void>;
```

#### Audio Playback

##### `preloadTracks()`

Initialize a playlist with multiple audio tracks for optimized playback:

```typescript
preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult>;
```

**Example:**

```typescript
const result = await CapacitorAudioEngine.preloadTracks({
  tracks: ['https://example.com/song1.mp3', 'file:///path/to/local/song2.m4a', 'https://example.com/song3.mp3'],
  preloadNext: true,
});

console.log('Preloaded tracks:', result.tracks);
```

##### `playAudio()`

Start playback of the current track or a specific track:

```typescript
playAudio(options?: PlayAudioOptions): Promise<void>;
```

##### `pauseAudio()`

Pause the current audio playback:

```typescript
pauseAudio(options?: PauseAudioOptions): Promise<void>;
```

##### `resumeAudio()`

Resume paused audio playback:

```typescript
resumeAudio(options?: ResumeAudioOptions): Promise<void>;
```

##### `stopAudio()`

Stop audio playback and reset to beginning:

```typescript
stopAudio(options?: StopAudioOptions): Promise<void>;
```

##### `seekAudio()`

Seek to a specific position in the current track:

```typescript
seekAudio(options: SeekOptions): Promise<void>;
```

**Example:**

```typescript
// Seek to 30 seconds
await CapacitorAudioEngine.seekAudio({ seconds: 30 });

// Seek in specific track
await CapacitorAudioEngine.seekAudio({
  seconds: 45,
  url: 'https://example.com/track.mp3',
});
```

##### `skipToNext()`

Skip to the next track in the playlist:

```typescript
skipToNext(): Promise<void>;
```

##### `skipToPrevious()`

Skip to the previous track in the playlist:

```typescript
skipToPrevious(): Promise<void>;
```

##### `skipToIndex()`

Jump to a specific track by index:

```typescript
skipToIndex(options: SkipToIndexOptions): Promise<void>;
```

**Example:**

```typescript
// Jump to the third track (index 2)
await CapacitorAudioEngine.skipToIndex({ index: 2 });
```

##### `getPlaybackInfo()`

Get comprehensive playback information:

```typescript
getPlaybackInfo(): Promise<PlaybackInfo>;
```

**Example:**

```typescript
const info = await CapacitorAudioEngine.getPlaybackInfo();
console.log('Current track:', info.currentTrack?.title);
console.log('Position:', `${info.currentPosition}s / ${info.duration}s`);
console.log('Playing:', info.isPlaying);
```

#### Event Handling

##### `addListener()`

Listen for recording and playback events:

```typescript
addListener<T extends AudioEventName>(
  eventName: T,
  callback: (event: AudioEventMap[T]) => void,
): Promise<PluginListenerHandle>;
```

**Recording Events:**

```typescript
// Duration changes during recording
await CapacitorAudioEngine.addListener('durationChange', (data) => {
  console.log('Recording duration:', data.duration, 'seconds');
});

// Recording errors
await CapacitorAudioEngine.addListener('error', (data) => {
  console.error('Recording error:', data.message);
});

// Wave level data (requires configureWaveform)
await CapacitorAudioEngine.addListener('waveLevel', (data) => {
  console.log('Audio level:', data.level); // 0.0 to 1.0
  console.log('Timestamp:', data.timestamp);
});

// Wave level initialization
await CapacitorAudioEngine.addListener('waveLevelInit', (data) => {
  console.log('Waveform initialized:', data.status);
});

// Permission status changes
await CapacitorAudioEngine.addListener('permissionStatusChanged', (data) => {
  console.log('Permission changed:', data.permissionType, data.status);
});
```

**Playback Events:**

```typescript
// Track changes
await CapacitorAudioEngine.addListener('trackChanged', (data) => {
  console.log('Track changed:', data.track.title, 'at index', data.index);
});

// Track completion
await CapacitorAudioEngine.addListener('trackEnded', (data) => {
  console.log('Track ended:', data.track.title);
});

// Playback start/pause
await CapacitorAudioEngine.addListener('playbackStarted', (data) => {
  console.log('Playback started:', data.track.title);
});

await CapacitorAudioEngine.addListener('playbackPaused', (data) => {
  console.log('Playback paused at:', data.position, 'seconds');
});

// Playback progress (every 500ms during playback)
await CapacitorAudioEngine.addListener('playbackProgress', (data) => {
  console.log(`Progress: ${data.currentPosition}/${data.duration}s`);
});

// Status changes
await CapacitorAudioEngine.addListener('playbackStatusChanged', (data) => {
  console.log('Status:', data.status, 'Playing:', data.isPlaying);
});

// Playback errors
await CapacitorAudioEngine.addListener('playbackError', (data) => {
  console.error('Playback error:', data.message);
});
```

##### `removeAllListeners()`

Remove all event listeners:

```typescript
removeAllListeners(): Promise<void>;
```

#### Usage Example

Here's a complete example showing how to use the plugin:

```typescript
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import { AudioSampleRate, AudioChannels, AudioBitrate, WaveLevelEmissionInterval } from 'capacitor-audio-engine';

class AudioManager {
  private isRecording = false;

  async initialize() {
    // Check permissions
    const permissions = await CapacitorAudioEngine.checkPermissions();
    if (!permissions.granted) {
      const result = await CapacitorAudioEngine.requestPermissions();
      if (!result.granted) {
        throw new Error('Permissions denied');
      }
    }

    // Set up event listeners
    await this.setupEventListeners();

    // Configure waveform monitoring
    await CapacitorAudioEngine.configureWaveform({
      EmissionInterval: WaveLevelEmissionInterval.FAST, // 200ms intervals
    });
  }

  async startRecording() {
    try {
      // Check if microphone is available
      const micStatus = await CapacitorAudioEngine.isMicrophoneBusy();
      if (micStatus.busy) {
        throw new Error(`Microphone busy: ${micStatus.reason}`);
      }

      // Start recording with high quality settings
      await CapacitorAudioEngine.startRecording({
        sampleRate: AudioSampleRate.CD_44K,
        channels: AudioChannels.MONO,
        bitrate: AudioBitrate.HIGH,
        maxDuration: 600, // 10 minutes max
      });

      this.isRecording = true;
      console.log('Recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async stopRecording() {
    try {
      const audioFile = await CapacitorAudioEngine.stopRecording();
      this.isRecording = false;
      console.log('Recording saved:', audioFile);
      return audioFile;
    } catch (error) {
      console.error('Failed to stop recording:', error);
    }
  }

  async playPlaylist(trackUrls: string[]) {
    try {
      // Preload tracks for better performance
      const preloadResult = await CapacitorAudioEngine.preloadTracks({
        tracks: trackUrls,
        preloadNext: true,
      });

      console.log('Preloaded tracks:', preloadResult.tracks);

      // Start playback
      await CapacitorAudioEngine.playAudio();
      console.log('Playback started');
    } catch (error) {
      console.error('Failed to start playback:', error);
    }
  }

  async switchMicrophone() {
    try {
      const mics = await CapacitorAudioEngine.getAvailableMicrophones();
      const externalMic = mics.microphones.find((mic) => mic.type === 'external');

      if (externalMic) {
        await CapacitorAudioEngine.switchMicrophone({
          microphoneId: externalMic.id,
        });
        console.log('Switched to external microphone');
      }
    } catch (error) {
      console.error('Failed to switch microphone:', error);
    }
  }

  private async setupEventListeners() {
    // Recording events
    await CapacitorAudioEngine.addListener('durationChange', (data) => {
      console.log('Duration:', data.duration);
    });

    await CapacitorAudioEngine.addListener('waveLevel', (data) => {
      // Update waveform visualization
      this.updateWaveform(data.level);
    });

    await CapacitorAudioEngine.addListener('error', (data) => {
      console.error('Recording error:', data.message);
    });

    // Playback events
    await CapacitorAudioEngine.addListener('trackChanged', (data) => {
      console.log('Now playing:', data.track.title);
    });

    await CapacitorAudioEngine.addListener('playbackProgress', (data) => {
      // Update progress bar
      this.updateProgress(data.currentPosition, data.duration);
    });
  }

  private updateWaveform(level: number) {
    // Update your waveform visualization UI
    // level is between 0.0 (silent) and 1.0 (loud)
  }

  private updateProgress(currentTime: number, duration: number) {
    // Update your progress bar UI
    const percentage = (currentTime / duration) * 100;
  }

  async cleanup() {
    await CapacitorAudioEngine.removeAllListeners();
    await CapacitorAudioEngine.destroyWaveform();
  }
}

// Usage
const audioManager = new AudioManager();
await audioManager.initialize();
```

> **Note:** All audio files are saved in M4A format (MPEG-4/AAC) across all platforms for consistency.

## � Troubleshooting

### Common Issues

**Permission Problems:**

- Make sure you've added the required permissions to your platform files
- Check if permissions were granted in device settings
- Use `openSettings()` to guide users to permission settings

**Recording Issues:**

- Check if another app is using the microphone with `isMicrophoneBusy()`
- Verify sufficient storage space is available
- Try switching microphones if external ones are available

**Playback Issues:**

- Ensure audio files are accessible and in supported formats
- Check network connectivity for remote audio files
- Verify the track was properly preloaded before playback

**Performance Issues:**

- Use `preloadTracks()` for better playback performance
- Configure appropriate `maxDuration` for long recordings
- Clean up listeners and resources when no longer needed

## 🛠️ Technical Details

### Platform-Specific Implementations

#### Android

- **Recording**: MediaRecorder with AAC codec in M4A container
- **Playback**: ExoPlayer for advanced playlist management
- **Format**: M4A/AAC (audio/m4a)
- **Storage**: App's external files directory under "Recordings" folder
- **Permissions**: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `POST_NOTIFICATIONS`
- **Microphone Management**: AudioManager.getDevices() for device enumeration
- **Background Support**: Foreground service for continuous recording/playback

#### iOS

- **Recording**: AVAudioRecorder with AAC codec in M4A container
- **Playback**: AVQueuePlayer for playlist management
- **Format**: M4A/AAC (audio/m4a)
- **Permissions**: NSMicrophoneUsageDescription in Info.plist
- **Microphone Management**: AVAudioSession.availableInputs for device enumeration
- **Background Support**: Background audio mode for continuous operation

#### Web (In Development)

- **Recording**: MediaRecorder API (when available)
- **Playback**: HTML5 Audio API
- **Format**: WebM/Opus for recording, various formats for playback
- **Limitations**: Limited microphone management, no waveform data

### Audio Quality Settings

The plugin uses optimized defaults that balance quality and file size:

- **Default**: 22.05kHz, 64kbps, mono (medium quality)
- **Voice Notes**: 16kHz, 32kbps, mono (smaller files)
- **High Quality**: 44.1kHz, 128kbps, mono (better audio)
- **Custom**: Any combination of supported sample rates, bitrates, and channels

### File Format Consistency

All platforms use M4A/AAC format for maximum compatibility:

- **MIME Type**: `audio/m4a`
- **Container**: MPEG-4 Part 14 (.m4a)
- **Codec**: Advanced Audio Coding (AAC)
- **Compatibility**: Excellent across all platforms and devices

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
