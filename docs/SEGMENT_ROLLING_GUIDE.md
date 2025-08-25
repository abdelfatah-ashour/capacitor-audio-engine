# Segment Rolling Audio Recording System

This document provides implementation examples for the segment rolling audio recording system that works on both iOS and Android platforms.

## Overview

The segment rolling system provides:

- **30-second segments**: Audio is recorded in 30-second chunks
- **Dynamic rolling window**: Buffer size is calculated based on `maxDuration` parameter
- **Automatic cleanup**: Oldest segments are automatically removed when the buffer is full
- **Seamless merging**: All segments are merged into a single audio file when recording stops
- **Rolling buffer management**: Only keeps the last N seconds of audio based on `maxDuration`
- **Memory efficient**: Prevents unlimited disk usage during long recordings

## Key Features

### maxDuration-Based Segment Rolling

When you provide a `maxDuration` parameter, the system:

1. **Enables segment rolling automatically** - No need for separate configuration
2. **Calculates optimal buffer size** - `ceil(maxDuration / 30)` segments are retained
3. **Maintains rolling window** - Only keeps segments needed for the specified duration
4. **Manages storage efficiently** - Automatically deletes old segments during recording
5. **Returns recent audio** - Final merged file contains the segments that were kept in the rolling buffer

### Examples of Buffer Management

- `maxDuration: 60` → keeps 2 segments (up to 60 seconds of recent audio)
- `maxDuration: 120` → keeps 4 segments (up to 120 seconds of recent audio)
- `maxDuration: 300` → keeps 10 segments (up to 300 seconds of recent audio)
- `maxDuration: 1800` → keeps 60 segments (up to 1800 seconds of recent audio)

## Platform Features

### iOS Implementation

- Uses `AVAudioRecorder` for segment recording
- `SegmentRollingManager` handles segment rotation and buffer management
- `AVAssetExportSession` for high-quality audio merging and precision trimming
- Thread-safe operations with dispatch queues
- Automatic cleanup of temporary segment files
- Rolling buffer management keeps only recent segments in memory
- Post-recording trimming for exact duration output

### Android Implementation

- Uses `MediaRecorder` for segment recording
- `SegmentRollingManager` handles segment rotation and buffer management
- `MediaMuxer` and `MediaExtractor` for audio merging
- `AudioFileProcessor` for precision post-recording trimming
- Concurrent collections for thread-safe buffer management
- Timer-based segment rotation
- Post-recording trimming for exact duration output

## Usage Examples

### Basic Usage

```typescript
import { CapacitorAudioEngine } from '@capacitor-community/audio-engine';

// Enable segment rolling by providing maxDuration (in seconds)
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
  maxDuration: 120, // 2 minutes - enables segment rolling with 2-minute rolling buffer
});

// Normal recording operations work the same
await CapacitorAudioEngine.pauseRecording();
await CapacitorAudioEngine.resumeRecording();

// Stop recording - automatically merges all buffered segments
const result = await CapacitorAudioEngine.stopRecording();
console.log('Final recording duration:', result.duration); // Duration of segments that were kept in buffer
```

### Long Recording with Efficient Storage

```typescript
// Record for 2 hours but only keep the last 5 minutes
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
  maxDuration: 300, // 5 minutes - only keeps last 5 minutes during recording
});

// Record for 2 hours...
// System automatically:
// - Creates segments every 30 seconds
// - Keeps only last 10 segments (300 seconds / 30 seconds = 10 segments)
// - Deletes old segments continuously during recording
// - Uses minimal disk space

const result = await CapacitorAudioEngine.stopRecording();
console.log(result.duration); // Duration of the segments that were kept (up to 300 seconds)
```

### Advanced Usage with Event Monitoring

```typescript
import { CapacitorAudioEngine } from '@capacitor-community/audio-engine';

class SegmentRollingRecorder {
  private isRecording = false;
  private durationListener: any;
  private maxDuration: number = 0;

  async startRecording(maxDurationMinutes: number = 10) {
    try {
      this.maxDuration = maxDurationMinutes * 60;

      const options = {
        sampleRate: 44100,
        channels: 1,
        bitrate: 128000,
        maxDuration: this.maxDuration, // Enables segment rolling and sets final duration
      };

      await CapacitorAudioEngine.startRecording(options);
      this.isRecording = true;

      // Monitor duration changes
      this.durationListener = await CapacitorAudioEngine.addListener('durationChange', (data) => {
        console.log(`Recording duration: ${data.duration}s`);

        // Calculate segment information
        const totalSegments = Math.floor(data.duration / 30);
        const maxSegments = Math.ceil(this.maxDuration / 30);
        const retainedSegments = Math.min(totalSegments, maxSegments);

        console.log(`Total segments created: ${totalSegments}`);
        console.log(`Segments retained: ${retainedSegments}/${maxSegments}`);
        console.log(`Disk usage: ~${retainedSegments * 30} seconds of audio`);
      });

      console.log(`Segment rolling recording started with ${maxDurationMinutes}-minute rolling window`);
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
  }

  async pauseRecording() {
    if (!this.isRecording) return;

    try {
      await CapacitorAudioEngine.pauseRecording();
      console.log('Recording paused');
    } catch (error) {
      console.error('Failed to pause recording:', error);
    }
  }

  async resumeRecording() {
    if (!this.isRecording) return;

    try {
      await CapacitorAudioEngine.resumeRecording();
      console.log('Recording resumed');
    } catch (error) {
      console.error('Failed to resume recording:', error);
    }
  }

  async stopRecording() {
    if (!this.isRecording) return;

    try {
      // Remove duration listener
      if (this.durationListener) {
        this.durationListener.remove();
        this.durationListener = null;
      }

      // Stop recording and get merged result
      const result = await CapacitorAudioEngine.stopRecording();
      this.isRecording = false;

      console.log('Recording stopped and merged:');
      console.log(`- Path: ${result.path}`);
      console.log(`- Duration: ${result.duration}s (from rolling buffer segments)`);
      console.log(`- Size: ${(result.size / 1024 / 1024).toFixed(2)} MB`);
      console.log(`- Sample Rate: ${result.sampleRate}Hz`);
      console.log(`- Channels: ${result.channels}`);
      console.log(`- Bitrate: ${result.bitrate}bps`);
      console.log(`- Contains: Audio from segments kept in rolling buffer`);

      return result;
    } catch (error) {
      console.error('Failed to stop recording:', error);
      this.isRecording = false;
    }
  }

  async getCurrentStatus() {
    try {
      const status = await CapacitorAudioEngine.getStatus();
      const duration = await CapacitorAudioEngine.getDuration();

      const totalSegments = Math.floor(duration.duration / 30);
      const maxSegments = Math.ceil(this.maxDuration / 30);
      const retainedSegments = Math.min(totalSegments, maxSegments);

      return {
        isRecording: status.isRecording,
        status: status.status,
        duration: duration.duration,
        maxDuration: this.maxDuration,
        totalSegmentsCreated: totalSegments,
        segmentsRetained: retainedSegments,
        maxSegmentsAllowed: maxSegments,
        effectiveDuration: Math.min(duration.duration, this.maxDuration),
      };
    } catch (error) {
      console.error('Failed to get status:', error);
      return null;
    }
  }
}

// Usage
const recorder = new SegmentRollingRecorder();

// Start recording with 5-minute rolling window
await recorder.startRecording(5);

// Monitor status
setInterval(async () => {
  const status = await recorder.getCurrentStatus();
  if (status) {
    console.log(`Recording: ${status.duration}s, Keeping last: ${status.maxDuration}s`);
    console.log(`Segments: ${status.segmentsRetained}/${status.maxSegmentsAllowed} retained`);
  }
}, 5000);

// Later...
await recorder.pauseRecording();
await recorder.resumeRecording();
const finalRecording = await recorder.stopRecording(); // Gets exactly 5 minutes (or less)
```

### Real-World Use Cases

#### 1. Voice Memo App (Keep Last 2 Minutes)

```typescript
// Perfect for voice memos - keeps the last 2 minutes during recording
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 64000,
  maxDuration: 120, // 2 minutes rolling buffer
});

// User can record for 10 minutes, but only last 2 minutes are kept during recording
```

#### 2. Meeting Recorder (Keep Last 30 Minutes)

```typescript
// Long meeting - keep only the last 30 minutes during recording
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
  maxDuration: 1800, // 30 minutes rolling buffer
});

// 3-hour meeting → final file: segments from last 30 minutes that were kept
```

#### 3. Security Audio Buffer (Keep Last 10 Minutes)

```typescript
// Rolling security audio - always have last 10 minutes available
await CapacitorAudioEngine.startRecording({
  sampleRate: 22050, // Lower quality for storage efficiency
  channels: 1,
  bitrate: 64000,
  maxDuration: 600, // 10 minutes rolling buffer
});

// Runs 24/7, always maintains last 10 minutes in buffer, minimal storage
```

````

### Long Recording Session Example

```typescript
// Example: 2-hour interview recording, keep only last 10 minutes
async function startLongInterview() {
  const recorder = new SegmentRollingRecorder();

  try {
    // Start 2-hour recording but only keep last 10 minutes in buffer
    await recorder.startRecording(10); // 10 minutes rolling window

    console.log('Interview recording started with segment rolling');
    console.log('- Segments: 30 seconds each');
    console.log('- Buffer: Last 10 minutes (20 segments max)');
    console.log('- Old segments automatically cleaned up during recording');
    console.log('- Final file will contain segments from the rolling buffer');

    // Record for 2 hours...
    // User can pause/resume as needed
    // System maintains rolling 10-minute buffer

    // When done, get final recording (segments that were kept in buffer)
    const result = await recorder.stopRecording();

    console.log(`Interview completed: ${result.duration}s (from rolling buffer of 2-hour session)`);
    console.log(`File size: ${(result.size / 1024 / 1024).toFixed(2)} MB`);
  } catch (error) {
    console.error('Interview recording failed:', error);
  }
}
````

## Technical Details

### Segment Management

- **Segment Duration**: 30 seconds per segment
- **Buffer Size**: Dynamic based on `maxDuration` - `ceil(maxDuration / 30)` segments
- **Rolling Window**: When buffer is full, oldest segments are automatically deleted
- **File Naming**: `segment_0.m4a`, `segment_1.m4a`, etc.

### Example Buffer Calculations

| maxDuration    | Segments Kept | Buffer Memory | Use Case            |
| -------------- | ------------- | ------------- | ------------------- |
| 60s (1 min)    | 2 segments    | ~60s audio    | Quick voice notes   |
| 300s (5 min)   | 10 segments   | ~300s audio   | Voice memos         |
| 600s (10 min)  | 20 segments   | ~600s audio   | Meeting highlights  |
| 1200s (20 min) | 40 segments   | ~1200s audio  | Extended recordings |
| 1800s (30 min) | 60 segments   | ~1800s audio  | Long interviews     |

### Audio Processing Flow

1. **Recording Start**: Creates first segment, starts 30-second timer
2. **Segment Rotation**: Every 30 seconds, closes current segment, starts new one
3. **Buffer Management**: Adds new segment to buffer, removes oldest if over limit
4. **File Cleanup**: Immediately deletes old segment files from disk during recording
5. **Recording Stop**: Merges all segments currently in buffer into single file
6. **Final Output**: Returns merged file containing segments that were kept in the rolling buffer

### Audio Merging Process

- **iOS**: Uses `AVAssetExportSession` with `AVMutableComposition` for high-quality segment merging
- **Android**: Uses `MediaMuxer` with `MediaExtractor` for segment merging
- **Quality**: Lossless merging preserves original audio quality
- **Format**: Output is always M4A with AAC encoding
- **Content**: Final file contains the segments that were kept in the rolling buffer

### Performance Considerations

- **Memory**: Low memory usage - only segment metadata kept in memory
- **Storage**: Dynamic storage based on `maxDuration` - never exceeds `ceil(maxDuration/30) * segment_size`
- **CPU**: Minimal overhead for segment rotation (every 30 seconds) and trimming (only on stop)
- **Battery**: Similar to normal recording, no significant impact
- **Efficiency**: Old segments deleted immediately, no accumulation of unused files

### Error Handling

- **Segment Failure**: If one segment fails, recording continues with next segment
- **Merge Failure**: Returns error, temporary segments preserved for manual recovery
- **Storage Full**: Automatic cleanup of oldest segments to make space for new ones
- **App Crash**: Temporary segments cleaned up on next app launch

### Benefits of maxDuration-Based Rolling Buffer

1. **Predictable Storage**: Never store more than `maxDuration` worth of segments
2. **Storage Efficiency**: Automatically manages disk usage during long recordings
3. **Recent Content**: Always maintains the most recent audio segments
4. **Memory Efficient**: Keeps only necessary segment references in memory
5. **Perfect for Continuous Recording**: Ideal for applications that need recent audio history without unlimited growth

## Migration from Linear Recording

To migrate existing code from linear recording to segment rolling:

```typescript
// Before (linear recording)
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
});

// After (segment rolling with rolling buffer management)
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
  maxDuration: 300, // Add this to enable segment rolling with 5-minute rolling buffer
});

// All other APIs remain the same
// pause, resume, stop, getDuration, getStatus work identically
// The difference: only recent segments are kept during recording, old ones are automatically deleted
```

## Key Features of the Segment Rolling System

### What the Implementation Provides:

- **Dynamic rolling window** based on `maxDuration`
- **Automatic buffer management** during recording with immediate cleanup of old segments
- **Post-recording precision trimming** - exact duration output matching `maxDuration`
- **Recent audio priority** (keeps recent segments, deletes old ones during recording)
- **Storage optimization** prevents unlimited disk usage during long recordings
- **Exact duration guarantee** - final output is precisely trimmed to `maxDuration` seconds

### Cross-Platform Consistency:

Both iOS and Android now provide identical behavior:

- **Rolling buffer management** during recording (30-second segments)
- **Automatic cleanup** of old segments when buffer reaches capacity
- **Post-recording trimming** using platform-specific audio processing (AVAssetExportSession on iOS, AudioFileProcessor on Android)
- **Exact duration output** - if you set `maxDuration: 120`, you get exactly 120 seconds of audio (or less if recording was shorter)

The segment rolling system is backward compatible - if `maxDuration` is not provided, the system uses traditional linear recording. When `maxDuration` is provided, you get enhanced segment rolling with intelligent buffer management, automatic storage cleanup during recording, and precise duration control.


## Rolling Window Behavior: Common maxDuration Cases

Below are concrete, easy-to-scan breakdowns of how the rolling window behaves for common maxDuration values. Remember:
- Segments are 30 seconds each.
- The rolling buffer keeps ceil(maxDuration / 30) most recent segments on disk; older segments are deleted during recording.
- When you stop, the output is trimmed precisely so its duration is at most maxDuration seconds (or less if you recorded for a shorter time).
- maxDuration does not auto-stop the recording; it defines the rolling window size and final output length.
- The implementation uses a continuous full-session file for instant availability at stop, then precision-trims to the last maxDuration window.

1) maxDuration = 5 seconds
- Segments kept: ceil(5 / 30) = 1 segment.
- On-disk buffer while recording: up to ~30 seconds (one segment). Older audio beyond that single segment is deleted as the next segment starts.
- At stop: you get exactly the last 5 seconds (precision-trimmed) if you recorded longer than 5 seconds; otherwise you get the full recorded duration.
- Notes: Using a 1-segment buffer is valid but not ideal for segment boundaries. For smoother rotation and redundancy, 60s+ (≥2 segments) is recommended.

2) maxDuration = 60 seconds
- Segments kept: ceil(60 / 30) = 2 segments.
- On-disk buffer while recording: ~60 seconds across two segments; the oldest segment is deleted when a new one begins.
- At stop: output is trimmed to the last 60 seconds (or less if total recording time is shorter). This is a good minimal rolling window for most apps.

3) maxDuration = 300 seconds (5 minutes)
- Segments kept: ceil(300 / 30) = 10 segments.
- On-disk buffer while recording: last 5 minutes only; older segments continuously deleted.
- At stop: precise 5-minute clip (or less if recorded for <5 minutes). This is a common choice for meeting highlights, voice notes, or crash-recovery buffers.
- Storage note: With 64 kbps AAC, 5 minutes is roughly ~2.4 MB of audio data plus container overhead (rough estimate; varies by content and encoder implementation).

4) maxDuration = 1200 seconds (20 minutes)
- Segments kept: ceil(1200 / 30) = 40 segments.
- On-disk buffer while recording: last 20 minutes maintained; older segments removed to cap storage.
- At stop: output trimmed to last 20 minutes. Suitable for extended sessions where you only need the recent context.

5) maxDuration = 14400 seconds (4 hours)
- Segments kept: ceil(14400 / 30) = 480 segments.
- On-disk buffer while recording: last 4 hours retained; segments older than that are deleted on rotation.
- At stop: output trimmed to last 4 hours. This is a very large window. Consider the following:
  - Performance and storage: While segments are cleaned up continuously, 4 hours implies a large number of segment files being created/rotated. Ensure sufficient storage headroom.
  - Device warnings: The Android implementation logs a warning for very large maxDuration (> 2 hours). Evaluate whether traditional linear recording is more suitable if you really need the entire session.
  - Crash recovery: You still benefit from finalized 30-second segments, but the number of in-scope segments is high.

Additional Notes
- Pause/Resume: The reported duration event excludes paused time; rolling buffer continues to manage segment retention based on actual captured audio time.
- Exactness: After stop, a precision trim ensures the final file duration matches maxDuration (when recorded time exceeds it), giving consistent outputs across platforms.
- Auto-stop: maxDuration does not auto-stop; you can record for hours and only the last maxDuration seconds are retained/returned.



## Scenario Examples: maxDuration vs. Actual Recording Time

These concrete scenarios pair a chosen maxDuration with an approximate total time you actually record. They clarify:
- How many 30s segments get created vs. retained during recording
- What remains on disk at any given time (rolling buffer)
- What you get when you stop (final output duration and source)

Remember:
- Segments are 30 seconds each.
- Rolling buffer keeps ceil(maxDuration / 30) most-recent segments during recording.
- On stop, Android uses the continuous full-session file for 0s availability, then precision-trims to return only the last maxDuration seconds (or less if you recorded for a shorter time).
- maxDuration does not auto-stop the session; it defines window size and final output length.

1) maxDuration = 5s, recorded ≈ 23s
- Segments created: ceil(23 / 30) = 1 segment (a single partial segment of ~23s, no rotation yet).
- Segments retained during recording: ceil(5 / 30) = 1 (only that single active segment).
- Buffer behavior: Nothing to delete (never reached a second segment). Disk usage ≈ 1 partial segment.
- On stop: Final file is ~23.0 seconds (no trimming needed because recorded time < maxDuration). Source is the continuous file, returned instantly and equal to the full captured time.

2) maxDuration = 60s, recorded ≈ 110s
- Segments created: ceil(110 / 30) = 4 (0–30, 30–60, 60–90, 90–110).
- Segments retained during recording: ceil(60 / 30) = 2 (only the last two are kept on disk; older ones are deleted on rotation).
- Buffer behavior: As the 3rd segment starts, the 1st gets deleted; as the 4th starts, the 2nd gets deleted. At the end, you keep segments ~60–90 and ~90–110.
- On stop: Final file is trimmed to the last 60.000 seconds of the session (from ~50s to ~110s in absolute timeline) using precision trim on the continuous file. Returned instantly, duration ≈ 60.0s.

3) maxDuration = 300s (5 min), recorded ≈ 400s
- Segments created: ceil(400 / 30) = 14.
- Segments retained during recording: ceil(300 / 30) = 10 (only the last 10 segments are kept).
- Buffer behavior: Disk retains only the most recent ~300s; anything older is deleted at each rotation.
- On stop: Final file is exactly the last 300 seconds (precision-trimmed). Returned instantly, duration ≈ 300.0s.

4) maxDuration = 1200s (20 min), recorded ≈ 1300s
- Segments created: ceil(1300 / 30) = 44.
- Segments retained during recording: ceil(1200 / 30) = 40.
- Buffer behavior: Only the most recent 40 segments (~20 minutes) remain on disk. Older segments continuously deleted.
- On stop: Final file is exactly the last 1200 seconds (precision-trimmed). Returned instantly, duration ≈ 1200.0s.

5) maxDuration = 14400s (4 hours), recorded ≈ 20000s (~5.56 hours)
- Segments created: ceil(20000 / 30) = 667.
- Segments retained during recording: ceil(14400 / 30) = 480.
- Buffer behavior: Only the most recent 4 hours (~480 segments) remain; the rest are deleted as you go.
- On stop: Final file is exactly the last 14400 seconds (precision-trimmed). Returned instantly, duration ≈ 14400.0s.
- Notes: This is a very large rolling window. Expect many segment rotations and deletes. Ensure sufficient storage headroom. Android logs a warning for windows > 2 hours; evaluate whether linear recording is more suitable if you truly need the entire session.

Tips
- Precision: Final trimming is done with MediaExtractor/MediaMuxer to honor the exact time range, independent of 30s boundaries.
- Short recordings: If you stop before reaching maxDuration, you simply get the full recorded time (no trimming needed).
- Pause/Resume: Reported duration excludes paused time; rolling keeps managing the buffer based on captured audio.


## Scenario Table: Rolling Window Summary

The table below summarizes how the rolling buffer behaves for common long-session cases. Assumptions:
- Segment length = 30s
- Segments created = ceil(totalSeconds / 30)
- Max segments kept = ceil(maxDuration / 30)
- Segments remaining at stop = min(segments created, max kept)
- Segments deleted during recording = segments created - segments remaining
- Final output duration (at stop) = min(maxDuration, totalSeconds), via precision trim if needed

| # | maxDuration (s) | Recording length (approx) | Total seconds recorded | Segments created (30s) | Max segments kept | Segments deleted | Segments remaining at stop | Final output (s) |
| - | ---------------- | ------------------------- | ---------------------- | ---------------------- | ----------------- | ---------------- | --------------------------- | ---------------- |
| 1 | 5               | ~1 hour                   | 3600                   | 120                    | 1                 | 119              | 1                           | 5                |
| 2 | 60              | ~1 hour                   | 3600                   | 120                    | 2                 | 118              | 2                           | 60               |
| 3 | 300             | ~3 hours                  | 10800                  | 360                    | 10                | 350              | 10                          | 300              |
| 4 | 1200            | ~4 hours                  | 14400                  | 480                    | 40                | 440              | 40                          | 1200             |
| 5 | 14400           | ~10 hours                 | 36000                  | 1200                   | 480               | 720              | 480                         | 14400            |

Notes
- The system does not auto-stop at maxDuration. You can record longer; old segments are deleted as you go, keeping only the most recent window.
- On Android, a continuous full-session file is closed instantly on stop (0s wait), then precision-trimmed to return exactly the last maxDuration seconds when the session exceeded that duration.
- Very small windows (e.g., 5s) imply a 1-segment buffer; this is valid but less smooth across boundaries. Windows with ≥2 segments (≥60s) are recommended when possible.
