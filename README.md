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
      - [`RecordingResult`](#recordingresult)
      - [`TrimOptions`](#trimoptions)
      - [`RecordingStatus`](#recordingstatus)
      - [`AudioRecordingEventName`](#audiorecordingeventname)
    - [Methods](#methods)
      - [Permission Management](#permission-management)
        - [`checkPermission()`](#checkpermission)
        - [`requestPermission()`](#requestpermission)
      - [Recording Control](#recording-control)
        - [`startRecording()`](#startrecording)
        - [`pauseRecording()`](#pauserecording)
        - [`resumeRecording()`](#resumerecording)
        - [`stopRecording()`](#stoprecording)
      - [Status \& Information](#status--information)
        - [`getDuration()`](#getduration)
        - [`getStatus()`](#getstatus)
      - [Audio Processing](#audio-processing)
        - [`trimAudio()`](#trimaudio)
      - [Event Handling](#event-handling)
        - [`addListener()`](#addlistener)
        - [`startMonitoring()`](#startmonitoring)
        - [`stopMonitoring()`](#stopmonitoring)
        - [`removeAllListeners()`](#removealllisteners)
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

> 💡 **Note:** Android and iOS are fully supported! Web support is coming soon - we're working on it! 🚧

## 🚀 Installation

### Prerequisites

- Node.js 14+ and npm
- Capacitor 5.0.0+
- iOS 13+ for iOS development
- Android 6.0+ (API level 24) for Android development

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

#### `RecordingResult`

```typescript
interface RecordingResult {
  path: string; // Where your file is stored
  webPath: string; // Web-friendly path
  uri: string; // Platform-specific URI
  mimeType: string; // File type (e.g., 'audio/mpeg')
  size: number; // File size in bytes
  duration: number; // Length in seconds
  sampleRate: number; // Sample rate (44100 Hz)
  channels: number; // Audio channels (1 = mono)
  bitrate: number; // Quality (128000 bps)
  createdAt: number; // When it was created
  filename: string; // The file's name
}
```

#### `TrimOptions`

```typescript
interface TrimOptions {
  path: string; // Your audio file
  startTime: number; // Where to start (in seconds)
  endTime: number; // Where to end (in seconds)
}
```

#### `RecordingStatus`

The status of the audio recorder.

```typescript
type RecordingStatus = 'idle' | 'recording' | 'paused';
```

#### `AudioRecordingEventName`

Event names for audio recording listeners.

```typescript
type AudioRecordingEventName = 'recordingInterruption';
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

Start recording audio.

```typescript
startRecording(): Promise<void>;
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

Stop recording and get the audio file.

```typescript
stopRecording(): Promise<RecordingResult>;
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
getStatus(): Promise<{ status: 'idle' | 'recording' | 'paused'; isRecording: boolean }>;
```

#### Audio Processing

##### `trimAudio()`

Trim an audio file to a specific duration.

```typescript
trimAudio(options: TrimOptions): Promise<RecordingResult>;
```

#### Event Handling

##### `addListener()`

Add a listener for recording interruptions.
`AudioRecordingEventName` can be `'recordingInterruption'`.
`PluginListenerHandle` is an interface from `@capacitor/core`.

```typescript
addListener(eventName: AudioRecordingEventName, callback: (data: { message: string }) => void): Promise<PluginListenerHandle>;
```

##### `startMonitoring()`

Start monitoring for recording interruptions.

```typescript
startMonitoring(): Promise<void>;
```

##### `stopMonitoring()`

Stop monitoring for recording interruptions.

```typescript
stopMonitoring(): Promise<void>;
```

##### `removeAllListeners()`

Remove all listeners for recording events.

```typescript
removeAllListeners(): Promise<void>;
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
- Format: MPEG_4 container with AAC codec
- MIME Type: 'audio/mpeg'
- Audio Source: MIC
- Storage: App's external files directory under "Recordings" folder
- Filename Format: "recording\_[timestamp].mp3"
- Required Permission: `android.permission.RECORD_AUDIO`
- Pause functionality requires Android N/API 24 or higher

#### iOS

- Uses AVAudioRecorder
- Format: M4A container with AAC codec
- MIME Type: 'audio/m4a'
- Quality: High
- Uses AVAssetExportSession for audio trimming

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

Found a bug? Have a feature request? Just want to chat? [Open an issue](https://github.com/abdelfatah-ashour/capacitor-native-audio/issues) on GitHub and we'll help you out!

---

Made with ❤️ by [Abdelfattah Ashour](https://github.com/abdelfattah-ashour)
