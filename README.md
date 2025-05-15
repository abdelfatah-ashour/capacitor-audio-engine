# @capacitor/native-audio 🎙️

Hey there! 👋 Welcome to the Native Audio plugin for Capacitor. This plugin makes it super easy to add high-quality audio recording to your mobile apps. Whether you're building a voice memo app, a podcast recorder, or just need to capture some audio, we've got you covered!

## ✨ What's Inside?

Here's what you can do with this plugin:

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

## 🚀 Let's Get Started!

First, let's install the plugin:

```bash
npm install @capacitor/native-audio
npx cap sync
```

## 📱 Platform Support

Here's what works where:

| Feature             | Android | iOS | Web |
| ------------------- | ------- | --- | --- |
| Recording           | ✅      | ✅  | 🔜  |
| Pause/Resume        | ✅      | ✅  | 🔜  |
| Permission Handling | ✅      | ✅  | 🔜  |
| Status Monitoring   | ✅      | ✅  | 🔜  |
| Audio Trimming      | ✅      | ✅  | 🔜  |

> 💡 **Note:** Android and iOS are fully supported! Web support is coming soon - we're working on it! 🚧

## 📖 API Documentation

### Interfaces

#### `RecordingResult`

This is what you get when you stop a recording:

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

Use this when you want to trim your audio:

```typescript
interface TrimOptions {
  path: string; // Your audio file
  startTime: number; // Where to start (in seconds)
  endTime: number; // Where to end (in seconds)
}
```

### Methods

#### `echo`

```typescript
echo(options: { value: string }): Promise<{ value: string }>;
```

Just a simple test method to make sure everything's working.

**Parameters:**

```typescript
{
  value: string; // Say something, and we'll say it back!
}
```

**Returns:**

```typescript
Promise<{
  value: string; // Your message, echoed back
}>;
```

---

#### `checkPermission`

```typescript
checkPermission(): Promise<{ granted: boolean }>;
```

Check if your app has permission to use the microphone.

**Returns:**

```typescript
Promise<{
  granted: boolean; // Can we use the mic?
}>;
```

**Platform Notes:**

- Web: Uses the browser's permission API
- Android: Checks for RECORD_AUDIO permission
- iOS: Checks AVAudioSession permissions

---

#### `requestPermission`

```typescript
requestPermission(): Promise<{ granted: boolean }>;
```

Ask the user for microphone permission.

**Returns:**

```typescript
Promise<{
  granted: boolean; // Did they say yes?
}>;
```

**Platform Notes:**

- Web: Shows browser permission prompt
- Android: Shows system permission dialog
- iOS: Shows system permission dialog

---

#### `startRecording`

```typescript
startRecording(): Promise<void>;
```

Start recording! 🎙️

**Returns:**

```typescript
Promise<void>; // Ready to record!
```

**Throws:**

- If you're already recording
- If you don't have permission
- If something goes wrong with the audio setup

**Platform Notes:**

- Web: Uses the MediaRecorder API
- Android: Uses MediaRecorder
- iOS: Uses AVAudioRecorder

---

#### `pauseRecording`

```typescript
pauseRecording(): Promise<void>;
```

Take a break from recording.

**Returns:**

```typescript
Promise<void>; // Recording paused!
```

**Throws:**

- If you're not recording

**Platform Notes:**

- Web: Pauses MediaRecorder
- Android: Pauses MediaRecorder (Android N+ only)
- iOS: Pauses AVAudioRecorder

---

#### `stopRecording`

```typescript
stopRecording(): Promise<RecordingResult>;
```

Stop recording and get your audio file.

**Returns:**

```typescript
Promise<RecordingResult>; // Your recording details
```

**Throws:**

- If you're not recording
- If something goes wrong saving the file

**Platform Notes:**

- Web: Gives you a blob URL
- Android: Gives you a content URL
- iOS: Gives you a capacitor file URL

---

#### `getDuration`

```typescript
getDuration(): Promise<{ duration: number }>;
```

How long have you been recording?

**Returns:**

```typescript
Promise<{
  duration: number; // Time in seconds
}>;
```

**Throws:**

- If you're not recording

**Platform Notes:**

- Web: Calculates from start time
- Android: Uses MediaRecorder
- iOS: Uses AVAudioRecorder

---

#### `getStatus`

```typescript
getStatus(): Promise<{ isRecording: boolean }>;
```

Are we recording right now?

**Returns:**

```typescript
Promise<{
  isRecording: boolean; // Yes or no?
}>;
```

**Platform Notes:**

- All platforms track recording state internally

---

#### `trimAudio`

```typescript
trimAudio(options: TrimOptions): Promise<RecordingResult>;
```

Cut your audio to just the good parts! ✂️

**Parameters:**

```typescript
{
  path: string; // Your audio file
  startTime: number; // Start here (seconds)
  endTime: number; // End here (seconds)
}
```

**Returns:**

```typescript
Promise<RecordingResult>; // Your trimmed audio
```

**Throws:**

- If something goes wrong trimming the file

**Platform Notes:**

- Web: Not supported yet (coming soon!)
- Android: Uses MediaExtractor and MediaMuxer
- iOS: Uses AVAssetExportSession

## 💡 Examples

### Basic Recording Example

```typescript
import { NativeAudio } from '@capacitor/native-audio';

async function startBasicRecording() {
  try {
    // Request permission first
    const { granted } = await NativeAudio.requestPermission();
    if (!granted) {
      console.error('Microphone permission not granted');
      return;
    }

    // Start recording
    await NativeAudio.startRecording();
    console.log('Recording started');

    // Stop recording after 5 seconds
    setTimeout(async () => {
      const recording = await NativeAudio.stopRecording();
      console.log('Recording saved:', recording);
    }, 5000);
  } catch (error) {
    console.error('Recording failed:', error);
  }
}
```

### Recording with Pause/Resume

```typescript
import { NativeAudio } from '@capacitor/native-audio';

class AudioRecorder {
  private isRecording = false;

  async startRecording() {
    try {
      const { granted } = await NativeAudio.requestPermission();
      if (!granted) return;

      await NativeAudio.startRecording();
      this.isRecording = true;
      console.log('Recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async pauseRecording() {
    if (!this.isRecording) return;

    try {
      await NativeAudio.pauseRecording();
      console.log('Recording paused');
    } catch (error) {
      console.error('Failed to pause recording:', error);
    }
  }

  async resumeRecording() {
    if (!this.isRecording) return;

    try {
      await NativeAudio.startRecording();
      console.log('Recording resumed');
    } catch (error) {
      console.error('Failed to resume recording:', error);
    }
  }

  async stopRecording() {
    if (!this.isRecording) return;

    try {
      const recording = await NativeAudio.stopRecording();
      this.isRecording = false;
      console.log('Recording saved:', recording);
      return recording;
    } catch (error) {
      console.error('Failed to stop recording:', error);
    }
  }
}
```

### Recording with Duration Monitoring

```typescript
import { NativeAudio } from '@capacitor/native-audio';

async function recordWithDuration() {
  try {
    await NativeAudio.requestPermission();
    await NativeAudio.startRecording();

    // Monitor duration every second
    const durationInterval = setInterval(async () => {
      const { duration } = await NativeAudio.getDuration();
      console.log(`Recording duration: ${duration.toFixed(1)} seconds`);
    }, 1000);

    // Stop after 10 seconds
    setTimeout(async () => {
      clearInterval(durationInterval);
      const recording = await NativeAudio.stopRecording();
      console.log('Final recording:', recording);
    }, 10000);
  } catch (error) {
    console.error('Recording failed:', error);
  }
}
```

### Audio Trimming Example

```typescript
import { NativeAudio } from '@capacitor/native-audio';

async function trimAudioFile(recordingPath: string) {
  try {
    // Trim the first 30 seconds of the recording
    const trimmed = await NativeAudio.trimAudio({
      path: recordingPath,
      startTime: 0,
      endTime: 30,
    });

    console.log('Trimmed audio:', trimmed);
    return trimmed;
  } catch (error) {
    console.error('Failed to trim audio:', error);
  }
}
```

### React Component Example

```typescript
import React, { useState, useEffect } from 'react';
import { NativeAudio } from '@capacitor/native-audio';

const AudioRecorder: React.FC = () => {
  const [isRecording, setIsRecording] = useState(false);
  const [duration, setDuration] = useState(0);
  const [recording, setRecording] = useState<any>(null);

  useEffect(() => {
    let interval: NodeJS.Timeout;

    if (isRecording) {
      interval = setInterval(async () => {
        const { duration } = await NativeAudio.getDuration();
        setDuration(duration);
      }, 1000);
    }

    return () => {
      if (interval) clearInterval(interval);
    };
  }, [isRecording]);

  const startRecording = async () => {
    try {
      const { granted } = await NativeAudio.requestPermission();
      if (!granted) return;

      await NativeAudio.startRecording();
      setIsRecording(true);
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  };

  const stopRecording = async () => {
    try {
      const result = await NativeAudio.stopRecording();
      setIsRecording(false);
      setRecording(result);
    } catch (error) {
      console.error('Failed to stop recording:', error);
    }
  };

  return (
    <div>
      <button onClick={isRecording ? stopRecording : startRecording}>
        {isRecording ? 'Stop Recording' : 'Start Recording'}
      </button>
      {isRecording && <p>Duration: {duration.toFixed(1)}s</p>}
      {recording && (
        <div>
          <p>Recording saved!</p>
          <p>Duration: {recording.duration}s</p>
          <p>Size: {(recording.size / 1024).toFixed(1)}KB</p>
        </div>
      )}
    </div>
  );
};

export default AudioRecorder;
```

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

Made with ❤️ by [Abdelfattah Ashour](https://github.com/abdelfatah-ashour)
