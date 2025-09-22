# Segment-rolling ANDROID

### Rolling Segments with Rotation

1. Record in compressed format directly (AAC in M4A) → efficient, small memory, avoids PCM overhead.

   Use MediaRecorder/MediaCodec + MediaMuxer.

2. Segment recording:
   - Write audio in rolling segments (default 1 minute each; configurable 1–5 minutes recommended).
   - Keep only the last N completed segments that cover X duration. Do not count the currently active segment toward retention.
   - Example: if X = 20 minutes, with 1-minute files, keep the last 20 completed segments. When a new segment starts, delete only the segment that falls out of the window.
   - Old segments are deleted automatically to save space.

3. On stop recording (Pre‑Merged Rolling File):
   - Maintain a rolling merged file (`rollingMerged.m4a`) during recording by appending each completed segment.
   - If merged duration exceeds X, head‑trim the merged file by the overflow to keep only the last X minutes.
   - On stop: finalize the active segment, append once, trim if needed, and return the merged file (rename/copy). Stop time becomes near‑constant and very small.

---

### 🔹 Options & Trade‑offs

- Rolling segments (with pre‑merged file) — recommended

  ✅ Simple, efficient

  ✅ Easy to manage memory/disk

  ✅ Near‑constant, sub‑second to low‑second stop time

  ✅ Work amortized during recording (append per segment)

  ⚠️ Slight extra I/O at each rotation (append + occasional head‑trim)

---

### 🔹 Suggested Implementation in Plugin

- Expose Capacitor plugin API like:

```ts
  startRecording(options?: RecordingOptions): Promise<void>;
  stopRecording(): Promise<AudioFileInfo>;
```

- Under the hood (Android):
  - Use MediaRecorder/MediaCodec + MediaMuxer → AAC/M4A, chunk duration = 1–5 mins.
  - Maintain a queue of completed segments (for retention) and a pre‑merged file (`rollingMerged.m4a`).
  - On rotation: finalize active → append to merged → if merged duration > X, head‑trim overflow → delete oldest segment if out of window.
  - On stop: finalize active → append once → optional head‑trim → return merged as final.

### 🔄 Recording Lifecycle Flow

```txt
┌───────────────────────┐
│   startRecording()    │
└───────────┬───────────┘
            │
            ▼
  ┌──────────────────┐
  │ Write Segment #1 │
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
  │ Head‑trim if > X │
  │ Return merged    │
  └───────┬──────────┘
          │
          ▼
  ┌──────────────────┐
  │ Return final file │
  └──────────────────┘

```

### 🚀 Recommendation

- The optimal solution is:
  Rolling compressed AAC/M4A segments + pre‑merged rolling file updated at rotation (append + head‑trim), with a single append on stop.

  This ensures:
  - Long recordings without memory/disk issues
  - Always keeping only the last X minutes
  - Near‑instant final file delivery (bounded and independent of total session length)

### ⚙️ Practical Settings

- Segment length: 2–5 minutes for best stop latency (fewer appends when stopping mid‑segment). 1 minute is acceptable if stop latency is already low.
- Bitrate: For voice, 96–128 kbps AAC to reduce I/O during append/trim.
- Storage: Prefer internal app storage for better throughput.

### 🧪 Debug & Telemetry

- Log per‑rotation timings: append time, head‑trim time, merged duration.
- Log stop timings: finalize active, append time, total assemble time.
- Keep counters for number of segments appended and trims performed to monitor health.

---

👉 Based on this, the best balance is:

Rolling compressed segments (AAC/M4A) with fast concat at stop.
