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
      - [Event Handling](#event-handling)
      - [Usage Example](#usage-example)
  - [🔧 Troubleshooting](#-troubleshooting)
    - [Common Issues](#common-issues)
  - [🛠️ Technical Details](#️-technical-details)
    - [Platform-Specific Implementations](#platform-specific-implementations)
      - [Web](#web)
      - [Android](#android-1)
      - [iOS](#ios-1)
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
- 🌐 Cross-platform support (Web coming soon!)
- 🎚️ Consistent audio quality:
  - Sample Rate: 44.1kHz
  - Channels: 1 (mono)
  - Bitrate: 128kbps

## 📱 Platform Support

| Feature             | Android | iOS | Web |
| ------------------- | ------- | --- | --- |
| Recording           | ✅      | ✅  | 🔜  |
| Pause/Resume        | ✅      | ✅  | 🔜  |
| Permission Handling | ✅      | ✅  | 🔜  |
| Status Monitoring   | ✅      | ✅  | 🔜  |
| Audio Trimming      | ✅      | ✅  | 🔜  |
| Segmented Recording | ✅      | ✅  | 🔜  |

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
}
```

#### `RecordingOptions`

```typescript
export interface RecordingOptions {
  maxDuration?: number;
  sampleRate?: number;
  channels?: number;
  bitrate?: number;
}
```

#### `SegmentedRecordingOptions`

```typescript
export interface SegmentedRecordingOptions extends RecordingOptions {
  segmentDuration?: number;
}
```

#### `RecordingStatus`

```typescript
type RecordingStatus = 'idle' | 'recording' | 'paused';
```

#### `AudioRecordingEventName`

```typescript
type AudioRecordingEventName =
  | 'recordingInterruption'
  | 'durationChange'
  | 'progress'
  | 'segmentProgress'
  | 'segmentMetadata'
  | 'error';
```

### Methods

#### Permission Management

##### `checkPermission()`

Check if your app has permission to use the microphone.

```typescript
checkPermission(): Promise<{ granted: boolean }>;
```

##### `requestPermission()`

Ask the user for microphone permission.

```typescript
requestPermission(): Promise<{ granted: boolean }>;
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

#### Event Handling

##### `addListener()`

Add a listener for recording events.

```typescript
addListener(
  eventName: AudioRecordingEventName,
  callback: (
    data:
      | RecordingInterruptionData
      | DurationChangeData
      | ProgressEventData
      | SegmentMetadataEventData
      | ErrorEventData,
  ) => void,
): Promise<PluginListenerHandle>;
```

##### `removeAllListeners()`

Remove all listeners for recording events.

```typescript
removeAllListeners(): Promise<void>;
```

> **Note:** The audio format is always `.m4a` (MPEG-4/AAC) on all platforms.

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

## 🛠️ Technical Details

### Platform-Specific Implementations

#### Web

- Uses MediaRecorder API
- Format: WebM container with Opus codec
- MIME Type: 'audio/webm;codecs=opus'
- Permission: Uses navigator.permissions.query API
- Audio trimming: Not supported (logs console message)

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

#### iOS

- Uses AVAudioRecorder
- Format: M4A container with AAC codec (MPEG-4/AAC, always .m4a)
- MIME Type: 'audio/m4a'
- Quality: High
- Uses AVAssetExportSession for audio trimming
- **Background Recording**: Supports continuous recording when app is backgrounded (requires 'audio' background mode)
- **Required Permission**: NSMicrophoneUsageDescription in Info.plist
- **Background Mode**: UIBackgroundModes with 'audio' capability

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
