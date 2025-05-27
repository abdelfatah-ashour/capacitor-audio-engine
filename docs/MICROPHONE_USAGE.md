# Microphone Detection and Switching Usage

This document demonstrates how to use the new microphone detection and switching functionality.

## New Methods Added

### 1. `isMicrophoneBusy()`

Check if the microphone is currently being used by another application.

```typescript
import { CapacitorAudioEngine } from 'capacitor-audio-engine';

async function checkMicrophoneStatus() {
  try {
    const result = await CapacitorAudioEngine.isMicrophoneBusy();
    console.log('Microphone busy:', result.busy);

    if (result.busy) {
      alert('Microphone is currently in use by another app');
    }
  } catch (error) {
    console.error('Error checking microphone status:', error);
  }
}
```

### 2. `getAvailableMicrophones()`

Get a list of all available microphones (internal and external).

```typescript
async function listMicrophones() {
  try {
    const result = await CapacitorAudioEngine.getAvailableMicrophones();
    console.log('Available microphones:', result.microphones);

    result.microphones.forEach((mic) => {
      console.log(`${mic.name} (${mic.type}): ${mic.description}`);
    });
  } catch (error) {
    console.error('Error getting microphones:', error);
  }
}
```

### 3. `switchMicrophone(options)`

Switch between microphones while keeping recording active.

```typescript
async function switchToExternalMic() {
  try {
    // First, get available microphones
    const micsResult = await CapacitorAudioEngine.getAvailableMicrophones();

    // Find external microphone
    const externalMic = micsResult.microphones.find((mic) => mic.type === 'external');

    if (externalMic) {
      // Switch to external microphone
      const switchResult = await CapacitorAudioEngine.switchMicrophone({
        microphoneId: externalMic.id,
      });

      console.log('Switched to microphone:', switchResult.microphoneId);
    } else {
      console.log('No external microphone found');
    }
  } catch (error) {
    console.error('Error switching microphone:', error);
  }
}
```

## Complete Example: Recording with Microphone Management

```typescript
import { CapacitorAudioEngine, MicrophoneInfo } from 'capacitor-audio-engine';

class AudioRecorderWithMicManagement {
  private availableMicrophones: MicrophoneInfo[] = [];
  private currentMicrophoneId: number | null = null;

  async initialize() {
    try {
      // Check microphone permission
      const permission = await CapacitorAudioEngine.requestPermission();
      if (!permission.granted) {
        throw new Error('Microphone permission denied');
      }

      // Check if microphone is busy
      const status = await CapacitorAudioEngine.isMicrophoneBusy();
      if (status.busy) {
        console.warn('Microphone is currently in use by another app');
      }

      // Get available microphones
      const micsResult = await CapacitorAudioEngine.getAvailableMicrophones();
      this.availableMicrophones = micsResult.microphones;

      console.log('Available microphones:', this.availableMicrophones);
    } catch (error) {
      console.error('Failed to initialize audio recorder:', error);
    }
  }

  async startRecording(preferredMicType: 'internal' | 'external' = 'internal') {
    try {
      // Find preferred microphone
      const preferredMic = this.availableMicrophones.find((mic) => mic.type === preferredMicType);

      if (preferredMic && preferredMic.id !== this.currentMicrophoneId) {
        // Switch to preferred microphone
        await CapacitorAudioEngine.switchMicrophone({
          microphoneId: preferredMic.id,
        });
        this.currentMicrophoneId = preferredMic.id;
        console.log(`Switched to ${preferredMicType} microphone: ${preferredMic.name}`);
      }

      // Start recording
      await CapacitorAudioEngine.startRecording({
        maxDuration: 300, // 5 minutes
        sampleRate: 44100,
        channels: 1,
        bitrate: 128000,
      });

      console.log('Recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async switchMicrophoneDuringRecording(microphoneId: number) {
    try {
      const switchResult = await CapacitorAudioEngine.switchMicrophone({
        microphoneId,
      });

      this.currentMicrophoneId = microphoneId;
      const mic = this.availableMicrophones.find((m) => m.id === microphoneId);
      console.log(`Switched to microphone: ${mic?.name} while recording`);

      return switchResult;
    } catch (error) {
      console.error('Failed to switch microphone during recording:', error);
      throw error;
    }
  }

  async stopRecording() {
    try {
      const result = await CapacitorAudioEngine.stopRecording();
      console.log('Recording stopped:', result);
      return result;
    } catch (error) {
      console.error('Failed to stop recording:', error);
      throw error;
    }
  }

  getMicrophoneList() {
    return this.availableMicrophones;
  }

  getCurrentMicrophone() {
    return this.availableMicrophones.find((mic) => mic.id === this.currentMicrophoneId);
  }
}

// Usage example
const recorder = new AudioRecorderWithMicManagement();

// Initialize the recorder
await recorder.initialize();

// Start recording with internal microphone
await recorder.startRecording('internal');

// Switch to external microphone during recording (if available)
const mics = recorder.getMicrophoneList();
const externalMic = mics.find((mic) => mic.type === 'external');
if (externalMic) {
  await recorder.switchMicrophoneDuringRecording(externalMic.id);
}

// Stop recording after some time
setTimeout(async () => {
  const audioFile = await recorder.stopRecording();
  console.log('Recording completed:', audioFile);
}, 10000); // Record for 10 seconds
```

## Platform Support

- **Android**: Full support for microphone detection and switching using AudioManager and AudioDeviceInfo APIs
- **iOS**: Full support using AVAudioSession APIs
- **Web**: Not supported (methods will throw errors)

## Error Handling

All methods can throw errors in the following cases:

- Permission denied
- Hardware not available
- Invalid microphone ID
- Platform not supported (web)

Always wrap calls in try-catch blocks for proper error handling.
