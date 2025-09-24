## Requirements Recap

1. **Long recording** — The recorder can run indefinitely.

2. **Keep only the last `X` duration** — Memory/disk shouldn’t grow endlessly.

3. **Instant final file on stop** — When the user taps stop, we must already have the merged file for the last `X` seconds, without a long wait.

---

## Core Challenges

- iOS `AVAudioRecorder` doesn’t support "rolling buffer" out-of-the-box.

- If you record a single file and then trim the last `X` seconds on stop, you’ll block the UI (slow export for long recordings).

- The solution must **pre-merge** or maintain rolling segments, so that stop is instant.

---

## Best Strategy: Rolling Segments + Pre-Merge

### How it works:

1. **Segment Rolling**
   - Record into **short segments** (e.g., 30s or 1min) using `AVAssetWriter`.

   - Store them in a queue (`Deque`/`CircularBuffer`).

   - Once the max total duration exceeds `X`, drop the oldest segment.

2. **Pre-Merge (Background Merge)**
   - Maintain a **background task** that incrementally merges these segments into a **pre-merged file** (always representing the last `X` seconds).

   - This way, the file is "ready-to-go" at any moment.

3. **Stop Recording**
   - On stop, simply return the latest **pre-merged file**.

   - No additional merge/export step → instant availability.

---

## Implementation Notes

- **Recording**
  - Use `AVAssetWriter` or `AVAudioEngine` with `AVAudioFile` to record compressed format (AAC in `.m4a`).

  - Avoid PCM/WAV unless absolutely needed (huge size).

- **Segment Rolling**
  - Fixed segment length (1 minute) for optimal performance.

  - Use a `DispatchQueue` for safe background file handling.

- **Pre-Merge**
  - Each time a segment finishes, enqueue a background task:
    - Concatenate segments into a **temp "pre-merged.m4a"** file.

    - Replace old pre-merged file once ready.

  - Keep only `⌈X/segment_length⌉ + 1` files max.

- **Capacitor Plugin**
  - Expose methods:
    - `startRecording(maxDuration?: number)` - maxDuration for rolling window

    - `stopRecording()` → returns URI of ready file

    - (optional) `onSegmentReady` for debug
