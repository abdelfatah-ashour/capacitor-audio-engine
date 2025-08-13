# Waveform Data Feature - Real-Time Audio Levels

The Capacitor Audio Engine plugin now supports real-time waveform data during recording, allowing you to create rich UI visualizations like WhatsApp-style voice message bars.

## Overview

- **Event Name**: `waveformData`
- **Emission Frequency**: ~30ms (33fps) for smooth UI animations
- **Data Format**: Array of normalized amplitude levels (0-1)
- **Default Resolution**: 32 bars (configurable up to 256)
- **Platforms**: iOS and Android

## Quick Start

### 1. Configure Waveform Settings (Optional)

```typescript
import { CapacitorAudioEngine } from '@capacitor-community/audio-engine';

// Configure number of waveform bars (default: 32)
await CapacitorAudioEngine.configureWaveform({
  numberOfBars: 64, // Higher resolution for smoother visualization
});
```

### 2. Listen for Waveform Data

```typescript
// Set up waveform data listener before starting recording
const waveformListener = await CapacitorAudioEngine.addListener('waveformData', (data) => {
  // data.levels is an array of normalized amplitude values (0-1)
  updateWaveformUI(data.levels);
});

// Start recording to begin receiving waveform data
await CapacitorAudioEngine.startRecording();
```

### 3. Implement Waveform Visualization

```typescript
function updateWaveformUI(levels: number[]) {
  // Example: Update CSS height based on amplitude levels
  levels.forEach((level, index) => {
    const bar = document.getElementById(`waveform-bar-${index}`);
    if (bar) {
      // Scale amplitude to height (0-100%)
      const height = Math.max(2, level * 100); // Minimum 2% height
      bar.style.height = `${height}%`;

      // Optional: Add color based on amplitude
      const intensity = Math.floor(level * 255);
      bar.style.backgroundColor = `rgb(${intensity}, 150, ${255 - intensity})`;
    }
  });
}
```

### 4. Clean Up

```typescript
// Stop recording and remove listener
await CapacitorAudioEngine.stopRecording();
waveformListener.remove();
```

## Complete Example

```typescript
import { CapacitorAudioEngine } from '@capacitor-community/audio-engine';

class WaveformRecorder {
  private waveformListener: any = null;
  private waveformBars: HTMLElement[] = [];

  async initialize() {
    // Configure waveform with higher resolution
    await CapacitorAudioEngine.configureWaveform({ numberOfBars: 48 });

    // Create waveform UI elements
    this.createWaveformUI();

    // Set up waveform data listener
    this.waveformListener = await CapacitorAudioEngine.addListener('waveformData', (data) => {
      this.updateWaveform(data.levels);
    });
  }

  private createWaveformUI() {
    const container = document.getElementById('waveform-container');
    for (let i = 0; i < 48; i++) {
      const bar = document.createElement('div');
      bar.className = 'waveform-bar';
      bar.id = `waveform-bar-${i}`;
      container?.appendChild(bar);
      this.waveformBars.push(bar);
    }
  }

  private updateWaveform(levels: number[]) {
    levels.forEach((level, index) => {
      if (this.waveformBars[index]) {
        const height = Math.max(2, level * 100);
        this.waveformBars[index].style.height = `${height}%`;

        // Smooth animation
        this.waveformBars[index].style.transition = 'height 0.1s ease-out';
      }
    });
  }

  async startRecording() {
    try {
      await CapacitorAudioEngine.startRecording({
        quality: 'medium',
        maxDuration: 300, // 5 minutes
      });
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async stopRecording() {
    try {
      const result = await CapacitorAudioEngine.stopRecording();
      console.log('Recording saved:', result.path);
      return result;
    } catch (error) {
      console.error('Failed to stop recording:', error);
    } finally {
      // Clean up listener
      if (this.waveformListener) {
        this.waveformListener.remove();
        this.waveformListener = null;
      }
    }
  }
}
```

## CSS Styling Example

```css
#waveform-container {
  display: flex;
  align-items: end;
  height: 60px;
  gap: 2px;
  padding: 10px;
  background: #f0f0f0;
  border-radius: 20px;
}

.waveform-bar {
  flex: 1;
  min-height: 2px;
  background: linear-gradient(to top, #4caf50, #81c784);
  border-radius: 1px;
  transition: height 0.1s ease-out;
}

/* Add pulsing effect for active recording */
.recording .waveform-bar {
  animation: pulse 1s ease-in-out infinite alternate;
}

@keyframes pulse {
  from {
    opacity: 0.7;
  }
  to {
    opacity: 1;
  }
}
```

## Configuration Options

### `configureWaveform(options)`

| Option         | Type     | Default | Description                      |
| -------------- | -------- | ------- | -------------------------------- |
| `numberOfBars` | `number` | `32`    | Number of amplitude bars (1-256) |

### Waveform Data Event

```typescript
interface WaveformData {
  levels: number[]; // Array of normalized amplitude values (0-1)
}
```

## Performance Notes

1. **Emission Frequency**: The plugin emits waveform data every ~30ms for smooth animations
2. **CPU Efficiency**: Audio processing is optimized with chunked PCM data analysis
3. **Memory Usage**: Only processed amplitude levels are sent to JavaScript (not raw PCM data)
4. **Platform Differences**:
   - **Android**: Uses AudioRecord with direct PCM access for real-time processing
   - **iOS**: Uses AVAudioEngine input node tap for PCM buffer analysis

## Best Practices

1. **UI Performance**: Use CSS transitions for smooth bar animations
2. **Battery Life**: Consider reducing `numberOfBars` for longer recordings
3. **Error Handling**: Always wrap recording calls in try-catch blocks
4. **Memory Management**: Remove event listeners when done recording
5. **Responsive Design**: Scale waveform visualization based on screen size

## Troubleshooting

### No Waveform Data Received

1. Ensure recording has started before expecting waveform events
2. Check that microphone permissions are granted
3. Verify the listener is set up before calling `startRecording()`

### Poor Performance

1. Reduce the number of waveform bars (try 16-32 instead of 64+)
2. Optimize your UI update function to avoid DOM manipulation bottlenecks
3. Use CSS transforms instead of changing element properties when possible

### Inconsistent Data

1. Waveform data is only emitted during active recording (not when paused)
2. The plugin automatically stops emitting when recording stops
3. Amplitude levels depend on environment noise and microphone sensitivity
