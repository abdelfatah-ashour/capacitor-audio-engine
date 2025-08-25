# Growing Waveform Levels Feature

## Overview

The Growing Waveform feature emits incremental audio levels during recording, allowing the UI to display a smooth left-to-right growing waveform visualization without overwriting past bars.

## Key Features

- **Incremental Emission**: Single level values are emitted every 25-40ms
- **Normalized Values**: All levels are between 0 and 1
- **Growing History**: Frontend maintains full history for smooth visualization
- **Light Payloads**: Only sends one float value per event, not arrays
- **Platform Consistency**: Same behavior across Android and iOS

## Event Structure

### Before (Array-based)

```json
{
  "levels": [0.0167, 0.0193, 0.0167, 0.0327]
}
```

### After (Growing single levels)

```json
{ "level": 0.0167 }
{ "level": 0.0193 }
{ "level": 0.0167 }
{ "level": 0.0327 }
{ "level": 0.24 }
```

## Implementation Details

### Android (Java)

```java
// Calculate RMS for entire buffer
long sum = 0;
for (int i = 0; i < samplesRead; i++) {
    long sample = buffer[i];
    sum += sample * sample;
}

double rms = Math.sqrt(sum / (double) samplesRead);
float level = (float) Math.min(1.0, rms / Short.MAX_VALUE);

// Emit single level
JSObject data = new JSObject();
data.put("level", level);
eventCallback.notifyListeners("waveformData", data);
```

### iOS (Swift)

```swift
// Calculate RMS for entire buffer
var sum: Float = 0.0
for i in 0..<frameLength {
    let sample = channelData[i]
    sum += sample * sample
}

let rms = sqrt(sum / Float(frameLength))
let level = min(1.0, max(0.0, rms))

// Emit single level
let data: [String: Any] = ["level": level]
eventCallback.notifyListeners("waveformData", data: data)
```

### TypeScript Interface

```typescript
export interface WaveformData {
  level: number; // Single normalized value 0-1
}
```

### Angular Demo Usage

```typescript
import { signal } from '@angular/core';

// Growing waveform history
protected readonly waveformHistory = signal<number[]>([]);

// Event listener
CapacitorAudioEngine.addListener('waveformData', (event: WaveformData) => {
  // Add new level to growing history
  this.waveformHistory.update(history => [...history, event.level]);
});

// Visualization data (last N bars)
protected readonly waveformVisualizationData = computed(() => {
  const levels = this.waveformHistory();
  const bars = this.waveformBars();

  // Show the most recent levels, up to the display bar count
  if (levels.length >= bars) {
    return levels.slice(-bars);
  } else {
    // Pad with zeros at the beginning
    const padding = new Array(bars - levels.length).fill(0);
    return [...padding, ...levels];
  }
});
```

## Benefits

### ✅ Smooth Growth Animation

- Left-to-right waveform growth
- No jarring updates or overwriting
- Natural recording visualization

### ✅ Performance Optimized

- Lightweight payloads (single float vs arrays)
- Reduced memory usage
- Efficient processing

### ✅ Frontend Control

- Full history management in JavaScript
- Configurable display length
- Easy to implement scroll/zoom effects

### ✅ Real-time Responsiveness

- 25fps emission rate (40ms intervals)
- Low latency audio-to-visual feedback
- Smooth for UI animations

## Configuration

The emission interval can be adjusted:

**Android**: `EMISSION_INTERVAL_MS = 40` (25fps)
**iOS**: `emissionIntervalMs: TimeInterval = 0.04` (25fps)

## Migration from Array-based System

1. Update event listeners to handle `event.level` instead of `event.levels`
2. Change storage from single array to growing history array
3. Update visualization logic to show recent levels
4. Rebuild TypeScript definitions with `npm run build`

## Usage Examples

### Basic Visualization

```typescript
<div class="waveform-bars">
  @for (level of waveformVisualizationData(); track $index) {
    <div
      class="waveform-bar"
      [style.height.%]="level * 100">
    </div>
  }
</div>
```

### History Management

```typescript
// Limit history to last 1000 levels (prevent memory growth)
CapacitorAudioEngine.addListener('waveformData', (event: WaveformData) => {
  this.waveformHistory.update((history) => {
    const newHistory = [...history, event.level];
    return newHistory.length > 1000 ? newHistory.slice(-1000) : newHistory;
  });
});
```

### Reset on New Recording

```typescript
async startRecording() {
  this.waveformHistory.set([]); // Clear previous recording data
  await CapacitorAudioEngine.startRecording(options);
}
```

This feature provides a much more natural and performant waveform visualization experience, perfect for WhatsApp-style voice message interfaces and other real-time audio applications.
