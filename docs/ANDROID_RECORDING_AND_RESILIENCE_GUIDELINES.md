# 🎤 Android Recording – Interruption Handling Guidelines

This document defines how the **Capacitor Audio Engine (Android)** should behave when **recording is interrupted** by microphone loss, audio focus changes, or system events.

---

## 📌 Scope

- **Platform:** Android only
- **Focus:** Handling **interruptions** during recording
- **Applies to:** Both **Segment Rolling** and **Single File** recording modes

---

## 🔄 Interruption Triggers

Recording may be interrupted by:

- **Phone calls** → system takes microphone
- **Audio focus loss** → another app plays audio or requests mic
- **Permissions revoked** → mic permission revoked at runtime
- **System events** → Doze mode, background restrictions

---

## 📡 Event Model

The plugin must emit clear events to JS layer:

- `statusChange` → transitions between `recording`, `paused`, `resumed`
- `error` → structured error event
  - Example codes:
    - `MIC_INTERRUPTED` → mic lost
    - `AUDIOFOCUS_LOST` → focus change
    - `PERMISSION_REVOKED` → mic permission removed

---

## 🔄 Handling Flow

### 1. Detect Interruption

- Listen to `AudioManager.OnAudioFocusChangeListener`
- Monitor `MediaRecorder` / `MediaCodec` error callbacks

### 2. On Interruption

- **Segment Rolling** → finalize current segment, mark state `paused`
- **Single File** → finalize current file, mark state `paused`
- Emit `error` + `statusChange(paused)`

### 3. Resume After Mic Available

- **Segment Rolling** → start new segment automatically
- **Single File** → start new file (append not supported natively)
- Emit `statusChange(resumed)`

### 4. On Stop Recording

- **Segment Rolling** → merge active queue into final `.m4a`
- **Single File** → merge partial files if multiple exist

---

## 📊 Behavior Summary

| Case               | Segment Rolling                      | Single File Recording              |
| ------------------ | ------------------------------------ | ---------------------------------- |
| Mic Interrupted    | Current segment finalized → `paused` | Current file finalized → `paused`  |
| Resume             | New segment started                  | New file started                   |
| Final File on Stop | Merge queue → 1 file                 | Merge files → 1 file (if >1)       |
| Data Loss Risk     | Few seconds max (unflushed buffer)   | Few seconds max (unflushed buffer) |

---

## ⚡ Best Practices

- Always **finalize audio on interruption** → no corruption risk
- Use **foreground service** to reduce background kills
- Provide **clear UI feedback** via `statusChange` events:
  - `"Recording paused – mic in use by another app"`
  - `"Recording resumed"`
- Ensure final file **always returned** at `stopRecording()` even after multiple interruptions
