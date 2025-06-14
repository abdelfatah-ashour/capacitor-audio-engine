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
      - [Segmented Recording](#segmented-recording)
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

- 🎯 Record high-quality audio on Android and iOS
- ⏯️ Pause and resume your recordings
- 📊 Monitor recording status in real-time
- 🔒 Handle permissions automatically
- ✂️ Trim your audio files
- 📝 Get detailed recording metadata
- 🎙️ **Microphone management** - Detect and switch between available microphones
- 🔍 **Microphone status** - Check if microphone is busy/in use by other apps
- 🎵 **Audio playback** - Play, pause, stop, and control recorded audio files
- 🎚️ **Playback controls** - Speed control, seeking, volume, and looping
- ⚡ **Audio preloading** - Preload audio files for faster playback start times
- 📡 **Real-time monitoring** - Track playback progress and status changes
- 🌐 Cross-platform support (Web coming soon!)
- 🎚️ Consistent audio quality:
  - Sample Rate: 44.1kHz
  - Channels: 1 (mono)
  - Bitrate: 128kbps

## 📱 Platform Support

| Feature              | Android | iOS | Web |
| -------------------- | ------- | --- | --- |
| Recording            | ✅      | ✅  | 🔜  |
| Pause/Resume         | ✅      | ✅  | 🔜  |
| Permission Handling  | ✅      | ✅  | 🔜  |
| Status Monitoring    | ✅      | ✅  | 🔜  |
| Audio Trimming       | ✅      | ✅  | 🔜  |
| Segmented Recording  | ✅      | ✅  | 🔜  |
| Microphone Detection | ✅      | ✅  | 🔜  |
| Microphone Switching | ✅      | ✅  | 🔜  |
| Audio Playback       | ✅      | ✅  | 🔜  |
| Playback Controls    | ✅      | ✅  | 🔜  |
| Audio Preloading     | ✅      | ✅  | ❌  |

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
   * Maximum duration in seconds to keep at the end of recording
   */
  maxDuration?: number;
  /**
   * Audio sample rate (Hz). Default: 44100
   */
  sampleRate?: number;
  /**
   * Number of audio channels. Default: 1 (mono)
   */
  channels?: number;
  /**
   * Audio bitrate (bps). Default: 128000
   */
  bitrate?: number;
  /**
   * Note: The audio format is always .m4a (MPEG-4/AAC) on all platforms.
   */
}
```

#### `SegmentedRecordingOptions`

```typescript
export interface SegmentedRecordingOptions extends RecordingOptions {
  /**
   * Duration of each segment in seconds (default: 30)
   */
  segmentDuration?: number;
}
```

#### `RecordingStatus`

```typescript
type RecordingStatus = 'idle' | 'recording' | 'paused';
```

#### `AudioRecordingEventName`

```typescript
type AudioRecordingEventName = 'recordingInterruption' | 'durationChange' | 'error';
```

#### `RecordingInterruptionData`

```typescript
export interface RecordingInterruptionData {
  message: string;
}
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

#### Segmented Recording

##### `startSegmentedRecording()`

Start recording audio in segments (chunks) that will be merged when stopped.

```typescript
startSegmentedRecording(options?: SegmentedRecordingOptions): Promise<void>;
```

##### `stopSegmentedRecording()`

Stop segmented recording and merge all segments into a single file.

```typescript
stopSegmentedRecording(): Promise<AudioFileInfo>;
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

##### `preload()`

Preload an audio file for playback to reduce latency when starting playback.

```typescript
preload(options: PreloadOptions): Promise<void>;
```

**Example:**

```typescript
await CapacitorAudioEngine.preload({
  uri: 'file:///path/to/audio.m4a',
  prepare: true,
});
```

##### `startPlayback()`

Start playing an audio file with optional playback controls.

```typescript
startPlayback(options: PlaybackOptions & { uri: string }): Promise<void>;
```

**Example:**

```typescript
await CapacitorAudioEngine.startPlayback({
  uri: 'file:///path/to/audio.m4a',
  speed: 1.5, // 1.5x speed
  startTime: 10, // Start at 10 seconds
  loop: false, // Don't loop
  volume: 0.8, // 80% volume
});
```

##### `pausePlayback()`

Pause the current audio playback.

```typescript
pausePlayback(): Promise<void>;
```

##### `resumePlayback()`

Resume paused audio playback.

```typescript
resumePlayback(): Promise<void>;
```

##### `stopPlayback()`

Stop the current audio playback completely.

```typescript
stopPlayback(): Promise<void>;
```

##### `seekTo()`

Seek to a specific time position in the current audio.

```typescript
seekTo(options: { time: number }): Promise<void>;
```

**Example:**

```typescript
// Seek to 30 seconds
await CapacitorAudioEngine.seekTo({ time: 30 });
```

##### `getPlaybackStatus()`

Get the current playback status and information.

```typescript
getPlaybackStatus(): Promise<AudioPlayerInfo>;
```

**Example:**

```typescript
const status = await CapacitorAudioEngine.getPlaybackStatus();
console.log('Current time:', status.currentTime);
console.log('Duration:', status.duration);
console.log('Status:', status.status);
console.log('Speed:', status.speed);
```

#### Event Handling

##### `addListener()`

Add a listener for recording or playback events.

```typescript
// Recording events
addListener(
  eventName: AudioRecordingEventName,
  callback: (data: RecordingInterruptionData | DurationChangeData | ErrorEventData) => void,
): Promise<PluginListenerHandle>;

// Playback events
addListener(
  eventName: AudioPlaybackEventName,
  callback: (data: PlaybackProgressData | PlaybackStatusData | PlaybackCompletedData | PlaybackErrorData) => void,
): Promise<PluginListenerHandle>;
```

**Playback Event Examples:**

```typescript
// Listen for playback progress updates
await CapacitorAudioEngine.addListener('playbackProgress', (data) => {
  console.log('Progress:', data.currentTime, '/', data.duration);
});

// Listen for playback status changes
await CapacitorAudioEngine.addListener('playbackStatusChange', (data) => {
  console.log('Status changed to:', data.status);
});

// Listen for playback completion
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

      // Start recording
      await CapacitorAudioEngine.startRecording({
        maxDuration: 300, // 5 minutes
        sampleRate: 44100,
        channels: 1,
        bitrate: 128000,
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
      await CapacitorAudioEngine.resumePlayback();
      console.log('Playback resumed');
    } catch (error) {
      console.error('Failed to resume playback:', error);
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
    await CapacitorAudioEngine.addListener('recordingInterruption', (data) => {
      console.log('Recording interrupted:', data.message);
    });

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
- Quality: High
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
