# iOS Rolling Segments Implementation

## Overview

This document describes the implementation of the iOS rolling segments recording feature, which provides instant stop recording capability regardless of recording duration. The implementation follows the strategy outlined in the iOS Segment Rolling Guide.

## Architecture

### Core Components

1. **RollingRecordingManager**: Main class implementing the rolling segments strategy
2. **CapacitorAudioEnginePlugin**: Updated to use RollingRecordingManager
3. **Constants**: Configuration constants for segment management
4. **Types**: Updated type definitions for new events and options

### Key Features

- **Rolling Segments**: Records in 1-minute segments for optimal performance
- **Pre-Merge Processing**: Background task maintains ready-to-go merged file
- **Instant Stop**: Always completes in < 1 second regardless of recording duration
- **Memory Management**: Rolling buffer prevents indefinite memory growth
- **Thread Safety**: All operations use dedicated dispatch queues

## Implementation Details

### Segment Rolling Strategy

```swift
// Segment configuration
private let segmentLength: TimeInterval = 60.0  // Fixed at 1 minute for optimal performance
private var maxSegments: Int = 10               // Maximum segments to keep
private var segmentURLs: [URL] = []             // Rolling buffer of segment files
```

### Pre-Merge Process

```swift
// Background pre-merge maintains ready-to-go file
private func performPreMerge() async {
    let segmentsToMerge = Array(segmentURLs)
    let mergedURL = try await mergeSegments(segmentsToMerge)
    preMergedURL = mergedURL  // Atomically replace
}
```

### Rolling Buffer Management

```swift
// Drop old segments when maxSegments exceeded
private func manageRollingBuffer() {
    while segmentURLs.count > maxSegments {
        if let oldSegmentURL = segmentURLs.first {
            try? FileManager.default.removeItem(at: oldSegmentURL)
            segmentURLs.removeFirst()
        }
    }
}
```

## API Changes

### New Recording Options

```typescript
interface RecordingOptions {
  // Existing options...
  maxDuration?: number; // Rolling window size
  // segmentLength is now fixed at 1 minute internally for optimal performance
}
```

### New Events

```typescript
// Segment completion event for monitoring
interface SegmentCompletedData {
  segmentIndex: number;
  duration: number;
}

// Listen for segment completion
CapacitorAudioEngine.addListener('segmentCompleted', (data) => {
  console.log(`Segment ${data.segmentIndex} completed: ${data.duration}s`);
});
```

## Usage Examples

### Basic Rolling Recording

```typescript
// Start recording with default 30-second segments
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
});
```

### Basic Recording

```typescript
// Basic recording with default settings (1-minute segments internally)
await CapacitorAudioEngine.startRecording({
  sampleRate: 44100,
  channels: 1,
  bitrate: 128000,
});
```

### Rolling Window with Max Duration

```typescript
// Keep only last 5 minutes (segments are fixed at 1 minute internally)
await CapacitorAudioEngine.startRecording({
  maxDuration: 300, // 5 minutes rolling window
});
```

### Long Recording with Memory Management

```typescript
// Long recording with automatic cleanup
await CapacitorAudioEngine.startRecording({
  maxDuration: 3600, // 1 hour rolling window
});

// Stop will always complete in < 1 second
const result = await CapacitorAudioEngine.stopRecording();
```

## Performance Characteristics

### Stop Recording Performance

- **Target**: < 1 second regardless of recording duration
- **Method**: Pre-merged file maintained in background
- **Benefit**: No UI blocking or long waits

### Memory Usage

- **Rolling Buffer**: Only keeps last N segments based on maxDuration
- **Automatic Cleanup**: Old segments removed when buffer exceeds limit
- **Pre-Merge**: Single merged file maintained, not all segments

### Disk Usage

- **Segment Files**: Temporary files cleaned up automatically
- **Pre-Merged File**: Single final file, segments removed after merge
- **Efficient**: No accumulation of files over time

## Configuration Constants

```swift
// Rolling Recording Configuration
static let defaultSegmentLength: TimeInterval = 30.0  // 30 seconds
static let maxSegmentLength: TimeInterval = 60.0      // 60 seconds max
static let minSegmentLength: TimeInterval = 10.0      // 10 seconds min
static let defaultMaxSegments: Int = 10               // Default max segments
```

## Thread Safety

All operations are thread-safe using dedicated dispatch queues:

```swift
// State operations
private let stateQueue = DispatchQueue(label: "rolling-recording-state", qos: .userInteractive)

// Segment operations
private let segmentQueue = DispatchQueue(label: "rolling-recording-segments", qos: .userInteractive)

// Merge operations
private let mergeQueue = DispatchQueue(label: "rolling-recording-merge", qos: .utility)
```

## Error Handling

The implementation includes comprehensive error handling:

- **Segment Creation Failures**: Graceful fallback and error reporting
- **Merge Failures**: Retry logic and error propagation
- **File System Errors**: Proper cleanup and error recovery
- **Audio Session Interruptions**: Automatic pause/resume handling

## Testing

The implementation includes comprehensive unit tests:

```swift
class RollingRecordingManagerTests: XCTestCase {
    func testRollingRecordingManagerInitialization()
    func testSegmentLengthValidation()
    func testMaxDurationConfiguration()
    func testPauseResumeFunctionality()
    func testResetRecordingFunctionality()
    func testAudioSettingsConfiguration()
    func testEdgeCaseSegmentLengths()
}
```

## Migration from Simple Recording

The RollingRecordingManager is a drop-in replacement for the simple RecordingManager:

1. **No API Changes**: Same delegate methods and callbacks
2. **Enhanced Features**: Additional segment completion events
3. **Better Performance**: Instant stop capability
4. **Memory Efficiency**: Rolling buffer management

## Troubleshooting

### Common Issues

1. **Segment Length**: Fixed at 1 minute for optimal performance
   - **Note**: Segment length is no longer configurable and is optimized internally

2. **Max Duration Too Large**: May consume excessive memory
   - **Solution**: Set reasonable maxDuration based on use case

3. **Merge Failures**: Background merge may fail
   - **Solution**: Implementation includes retry logic and fallback

### Debug Events

```typescript
// Listen for segment completion to monitor performance
CapacitorAudioEngine.addListener('segmentCompleted', (data) => {
  console.log(`Segment ${data.segmentIndex}: ${data.duration}s`);
});

// Monitor duration updates
CapacitorAudioEngine.addListener('durationChange', (data) => {
  console.log(`Total duration: ${data.duration}s`);
});
```

## Future Enhancements

Potential future improvements:

1. **Dynamic Memory Management**: Adjust buffer size based on available memory
2. **Compression**: Compress old segments to save disk space
3. **Network Recording**: Support for streaming segments
4. **Analytics**: Detailed performance metrics and monitoring

## Conclusion

The iOS rolling segments implementation provides a robust, performant solution for long-duration audio recording with instant stop capability. The rolling buffer approach ensures memory efficiency while the pre-merge process guarantees fast stop times regardless of recording duration.
