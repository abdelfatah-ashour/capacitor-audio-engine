import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type AudioRecordingEventName = 'recordingInterruption' | 'durationChange' | 'error';

export interface AudioRecordingEvent<T = any> {
  eventName: AudioRecordingEventName;
  payload: T;
}

export type RecordingInterruptionDataType = AudioRecordingEvent<RecordingInterruptionData>;
export type DurationChangeDataType = AudioRecordingEvent<DurationChangeData>;
export type ErrorEventDataType = AudioRecordingEvent<ErrorEventData>;

export type AudioRecordingEventTypes = RecordingInterruptionDataType | DurationChangeDataType | ErrorEventDataType;

export type AudioRecordingEventMap = {
  recordingInterruption: RecordingInterruptionDataType;
  durationChange: DurationChangeDataType;
  error: ErrorEventDataType;
};

export interface RecordingInterruptionData {
  message: string;
}

export interface DurationChangeData {
  duration: number;
}

export interface ErrorEventData {
  message: string;
  code?: string | number;
  details?: any;
}

export interface RecordingOptions {
  /**
   * Maximum duration in seconds to keep at the end of recording
   */
  maxDuration?: number;
  /**
   * Audio sample rate (Hz). Default: 44100
   */
  sampleRate?: number;
  /**
   * Number of audio channels. Default: 1 (mono)
   */
  channels?: number;
  /**
   * Audio bitrate (bps). Default: 128000
   */
  bitrate?: number;
  /**
   * Note: The audio format is always .m4a (MPEG-4/AAC) on all platforms.
   */
}

export interface SegmentedRecordingOptions extends RecordingOptions {
  /**
   * Duration of each segment in seconds (default: 30)
   */
  segmentDuration?: number;
}

export interface AudioFileInfo {
  path: string;
  webPath: string;
  uri: string;
  mimeType: string;
  size: number;
  duration: number;
  sampleRate: number;
  channels: number;
  bitrate: number;
  createdAt: number;
  filename: string;
}

/**
 * Interface for the Native Audio Plugin that provides audio recording capabilities.
 *
 * Platform-specific implementations:
 * - Web: Uses MediaRecorder API with WebM/Opus format
 * - Android: Uses MediaRecorder with AAC format in MP4 container
 * - iOS: Uses AVAudioRecorder with AAC format in M4A container
 *
 * Common settings across platforms:
 * - Sample Rate: 44.1kHz
 * - Channels: 1 (mono)
 * - Bitrate: 128kbps
 */
export interface CapacitorAudioEnginePlugin {
  /**
   * Test method to verify plugin functionality.
   * @param options - Echo options
   * @param options.value - String value to echo back
   * @returns Promise that resolves with the echoed value
   */
  echo(options: { value: string }): Promise<{ value: string }>;

  /**
   * Check if the app has microphone permission.
   * @returns Promise that resolves with an object containing the permission status
   * @property {boolean} granted - Whether microphone permission is granted
   * @platform web Uses navigator.permissions.query API
   * @platform android Uses ContextCompat.checkSelfPermission with RECORD_AUDIO permission
   * @platform ios Uses AVAudioSession.recordPermission
   */
  checkPermission(): Promise<{ granted: boolean }>;

  /**
   * Request microphone permission from the user.
   * @returns Promise that resolves with an object containing the permission status
   * @property {boolean} granted - Whether microphone permission was granted
   * @platform web Uses navigator.mediaDevices.getUserMedia API
   * @platform android Uses ActivityCompat.requestPermissions with RECORD_AUDIO permission
   * @platform ios Uses AVAudioSession.requestRecordPermission
   */
  requestPermission(): Promise<{ granted: boolean }>;

  /**
   * Start recording audio from the device's microphone.
   * @param options - Recording options
   * @param options.maxDuration - Maximum duration in seconds to keep at the end of recording
   * @returns Promise that resolves when recording starts successfully
   * @throws {Error} If recording is already in progress
   * @throws {Error} If microphone permission is not granted
   * @throws {Error} If audio session setup fails
   * @platform web Uses MediaRecorder API
   * @platform android Uses MediaRecorder
   * @platform ios Uses AVAudioRecorder
   */
  startRecording(options?: RecordingOptions): Promise<void>;

  /**
   * Pause the current recording.
   * @returns Promise that resolves when recording is paused successfully
   * @throws {Error} If no active recording exists or if recording is already paused
   * @platform web Uses MediaRecorder.pause()
   * @platform android Uses MediaRecorder.pause() (Android N/API 24+ only)
   * @platform ios Uses AVAudioRecorder.pause()
   */
  pauseRecording(): Promise<void>;

  /**
   * Resume the current recording if it was previously paused.
   * @returns Promise that resolves when recording is resumed successfully
   * @throws {Error} If no active recording exists or if recording is not paused
   * @platform web Uses MediaRecorder.resume()
   * @platform android Uses MediaRecorder.resume() (Android N/API 24+ only)
   * @platform ios Uses AVAudioRecorder.record()
   */
  resumeRecording(): Promise<void>;

  /**
   * Stop the current recording and get the recorded file information.
   * @returns Promise that resolves with the recorded audio file details
   * @throws {Error} If no active recording exists
   * @throws {Error} If file processing fails
   */
  stopRecording(): Promise<AudioFileInfo>;

  /**
   * Get the current recording duration.
   * @returns Promise that resolves with the current duration in seconds
   * @throws {Error} If no active recording exists
   * @property {number} duration - Recording duration in seconds
   * @platform web Not supported - returns 0
   * @platform android Uses MediaRecorder.getMaxAmplitude()
   * @platform ios Uses AVAudioRecorder.currentTime
   */
  getDuration(): Promise<{ duration: number }>;

  /**
   * Get the current recording status.
   * @returns Promise that resolves with the current recording status
   * @property {RecordingStatus} status - The current state of the recorder
   * @property {boolean} isRecording - True if the recording session is active
   * @property {number} currentSegment - The current segment number
   * @property {number} duration - The current recording duration in seconds
   */
  getStatus(): Promise<{
    status: RecordingStatus;
    isRecording: boolean;
    currentSegment: number;
    duration: number;
  }>;

  /**
   * Trim an audio file to the specified start and end times.
   * @param options - Trim options
   * @param options.uri - URI of the audio file to trim
   * @param options.start - Start time in seconds
   * @param options.end - End time in seconds
   * @returns Promise that resolves with the trimmed audio file details
   * @throws {Error} If file processing fails
   * @platform web Not supported
   * @platform android Uses MediaExtractor and MediaMuxer
   * @platform ios Uses AVAssetExportSession
   */
  trimAudio(options: { uri: string; start: number; end: number }): Promise<AudioFileInfo>;

  /**
   * Add a listener for recording events
   * @param eventName - The name of the event to listen to
   * @param callback - The callback to invoke when the event occurs
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported
   * @platform android Uses MediaRecorder events and duration monitoring
   * @platform ios Uses AVAudioSession notifications and duration monitoring
   */
  addListener<T extends AudioRecordingEventName>(
    eventName: T,
    callback: (event: AudioRecordingEventMap[T]) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners
   * @returns Promise that resolves when all listeners are removed
   */
  removeAllListeners(): Promise<void>;
}
