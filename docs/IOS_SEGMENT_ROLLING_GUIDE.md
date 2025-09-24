# 🎤 Segment-Rolling iOS

## Rolling Segments with Rotation

1. Record in compressed format directly (**AAC in M4A**) → efficient, small memory footprint, avoids uncompressed PCM overhead.
   - Use **AVAudioRecorder** (simpler) or **AVAssetWriter** (recommended for precise control).

2. **Segment recording**:
   - Write audio in **rolling segments** (default **1 minute** each; configurable **1–5 minutes** recommended).
   - Keep only the **last N completed segments** that cover total duration `X`.
   - Do **not** count the currently active segment toward retention.
   - Example: if `X = 20 minutes`, with 1-minute files → keep last **20 completed segments**.
   - When a new segment starts, delete only the one that falls out of the retention window.
   - Old segments are deleted automatically to save space.

3. **On stop recording (Pre-Merged Rolling File)**:
   - Maintain a **rolling merged file** (`rollingMerged.m4a`) during recording by appending each completed segment.
   - If merged duration exceeds `X`, **head-trim** the merged file by the overflow → keep only the last `X` minutes.
   - On stop:
     - Finalize the active segment
     - Append it once
     - Trim if needed
     - Return the merged file (rename/copy).
   - Stop time is near-constant and very small.

---

## 🔹 Options & Trade-offs

**Rolling segments (with pre-merged file) — recommended**

- ✅ Simple, efficient
- ✅ Easy to manage memory/disk
- ✅ Near-constant, sub-second stop latency
- ✅ Work amortized during recording (append per segment)
- ⚠️ Slight extra I/O at each rotation (append + occasional head-trim)

---

## 🔹 Suggested Implementation in Plugin

- Expose Capacitor plugin API like:

```ts
startRecording(options?: RecordingOptions): Promise<void>;
stopRecording(): Promise<AudioFileInfo>;
```

### Under the hood (iOS):

    - Use AVAssetWriter → AAC/M4A, chunk duration = 1–5 mins.
    - Maintain a queue of completed segments (for retention) and a rolling merged file (rollingMerged.m4a).
    - On rotation:
      - Finalize active segment
      - Append to merged file
      - If merged duration > X, head-trim overflow
      - Delete oldest segment if out of window
    - On stop:
      - Finalize active segment
      - Append once
      - Optional head-trim

### 🔄 Recording Lifecycle Flow (iOS)

    ```txt
    ┌───────────────────────┐
    │   startRecording()    │
    └───────────┬───────────┘
                │
                ▼
      ┌──────────────────┐
      │ Write Segment #1 │ (AVAssetWriter)
      └───────┬──────────┘
              │
              ▼
      ┌──────────────────┐
      │ Write Segment #2 │
      └───────┬──────────┘
              │
              ▼
      ┌──────────────────┐
      │ Rolling Queue     │
      │ Keep last N files │ (completed only)
      │ Delete old files  │
      └───────┬──────────┘
              │
              ▼
    ┌───────────────────────┐
    │   stopRecording()     │
    └───────────┬───────────┘
                │
                ▼
      ┌──────────────────┐
      │ Append active    │
      │ Head-trim if > X │
      │ Return merged    │
      └───────┬──────────┘
              │
              ▼
      ┌──────────────────┐
      │ Return final file │
      └──────────────────┘
    ```

#### 🚀 Recommendation

    - Use rolling AAC/M4A segments + pre-merged rolling file, updated at each rotation (append + trim).
    - On stop, perform one last append and return instantly.

#### This ensures:

    - Long recordings without memory/disk blowup
    - Always keeping only the last X minutes
    - Near-instant stop time, independent of session length

#### ⚙️ Practical Settings (iOS)

      - Segment length: 2–5 minutes for fewer appends; 1 minute is fine if stop latency is acceptable.
      - Bitrate: 96–128 kbps AAC (optimized for voice, reduces I/O).
      - Storage: Use app sandbox (Documents/Library) for throughput.
      - Background mode: Enable Audio background capability in Xcode.

#### 👉 Best balance for iOS:

Rolling AAC/M4A segments + pre-merged rolling file, with append + trim per rotation, and a single append at stop.
