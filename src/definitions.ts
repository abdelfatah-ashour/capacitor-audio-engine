import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type AudioRecordingEventName = 'recordingInterruption';

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
   * @returns Promise that resolves when recording starts successfully
   * @throws {Error} If recording is already in progress
   * @throws {Error} If microphone permission is not granted
   * @throws {Error} If audio session setup fails
   * @platform web Uses MediaRecorder API
   * - Format: WebM container with Opus codec
   * - Sample Rate: 44.1kHz
   * - Channels: 1 (mono)
   * - Bitrate: 128kbps
   * @platform android Uses MediaRecorder
   * - Format: MPEG_4 container with AAC codec
   * - Sample Rate: 44.1kHz
   * - Channels: 1 (mono)
   * - Bitrate: 128kbps
   * - Audio Source: MIC
   * @platform ios Uses AVAudioRecorder
   * - Format: M4A container with AAC codec
   * - Sample Rate: 44.1kHz
   * - Channels: 1 (mono)
   * - Bitrate: 128kbps
   * - Quality: High
   */
  startRecording(): Promise<void>;

  /**
   * Pause the current recording.
   * @returns Promise that resolves when recording is paused successfully
   * @throws {Error} If no active recording exists or if recording is already paused.
   * @platform web Uses MediaRecorder.pause()
   * @platform android Uses MediaRecorder.pause() (Android N/API 24+ only)
   * @platform ios Uses AVAudioRecorder.pause()
   */
  pauseRecording(): Promise<void>;

  /**
   * Resume the current recording if it was previously paused.
   * @returns Promise that resolves when recording is resumed successfully
   * @throws {Error} If no active recording exists, if the recording is not paused, or if resumption fails.
   * @platform web Uses MediaRecorder.resume()
   * @platform android Uses MediaRecorder.resume() (Android N/API 24+ only)
   * @platform ios Uses AVAudioRecorder.record() (which resumes if paused)
   */
  resumeRecording(): Promise<void>;

  /**
   * Stop the current recording and get the recorded file information.
   * @returns Promise that resolves with the recorded audio file details
   * @throws {Error} If no active recording exists
   * @throws {Error} If file processing fails
   * @property {string} path - Absolute file system path to the recording
   * @property {string} webPath - Web-accessible path for the recording
   *                             Web: blob:// URL
   *                             Android: content:// URL via FileProvider
   *                             iOS: capacitor://localhost/_capacitor_file_/path
   * @property {string} uri - Platform-specific URI for file access
   *                         Web: blob:// URL
   *                         Android: content:// URL via FileProvider
   *                         iOS: file:// URL
   * @property {string} mimeType - MIME type of the audio file
   *                               Web: 'audio/webm;codecs=opus'
   *                               Android: 'audio/mpeg'
   *                               iOS: 'audio/m4a'
   * @property {number} size - Size of the file in bytes
   * @property {number} duration - Recording duration in seconds
   * @property {number} sampleRate - Sample rate in Hz (44100)
   * @property {number} channels - Number of audio channels (1=mono)
   * @property {number} bitrate - Audio bitrate in bits per second (128000)
   * @property {number} createdAt - Unix timestamp when recording was created
   * @property {string} filename - Name of the recorded file with extension
   */
  stopRecording(): Promise<{
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
  }>;

  /**
   * Get the current recording duration.
   * @returns Promise that resolves with the current duration in seconds
   * @throws {Error} If no active recording exists
   * @property {number} duration - Recording duration in seconds
   * @platform web Calculated from recording start time
   * @platform android Uses MediaRecorder.getMaxAmplitude()
   * @platform ios Uses AVAudioRecorder.currentTime
   */
  getDuration(): Promise<{ duration: number }>;

  /**
   * Get the current recording status.
   * @returns Promise that resolves with the current recording status.
   * @property {string} status - The current state of the recorder: "idle", "recording", or "paused".
   * @property {boolean} isRecording - True if the recording session is active (i.e., status is "recording" or "paused").
   * @platform web Tracks internal state
   * @platform android Tracks internal state
   * @platform ios Tracks internal state, mapping AVAudioRecorder states
   */
  getStatus(): Promise<{ status: RecordingStatus; isRecording: boolean }>;

  /**
   * Trim an audio file to the specified start and end times.
   * @param options - Trim options
   * @param options.path - Path to the audio file to trim
   * @param options.startTime - Start time in seconds
   * @param options.endTime - End time in seconds
   * @returns Promise that resolves with the trimmed audio file details
   * @throws {Error} If file processing fails
   * @property {string} path - Absolute file system path to the trimmed file
   * @property {string} webPath - Web-accessible path for the trimmed file
   *                             Web: blob:// URL
   *                             Android: content:// URL via FileProvider
   *                             iOS: capacitor://localhost/_capacitor_file_/path
   * @property {string} uri - Platform-specific URI for file access
   *                         Web: blob:// URL
   *                         Android: content:// URL via FileProvider
   *                         iOS: file:// URL
   * @property {string} mimeType - MIME type of the audio file
   *                               Web: 'audio/webm;codecs=opus'
   *                               Android: 'audio/mpeg'
   *                               iOS: 'audio/m4a'
   * @property {number} size - Size of the file in bytes
   * @property {number} duration - Duration in seconds
   * @property {number} sampleRate - Sample rate in Hz (44100)
   * @property {number} channels - Number of audio channels (1=mono)
   * @property {number} bitrate - Audio bitrate in bits per second (128000)
   * @property {number} createdAt - Unix timestamp when file was created
   * @property {string} filename - Name of the trimmed file with extension
   * @platform web Not supported, logs console message
   * @platform android Uses MediaExtractor and MediaMuxer
   * @platform ios Uses AVAssetExportSession
   */
  trimAudio(options: { path: string; startTime: number; endTime: number }): Promise<{
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
  }>;

  /**
   * Add a listener for recording interruptions
   * @param eventName - The name of the event to listen to. Currently only 'recordingInterruption' is supported.
   * @param callback - The callback to invoke when the event occurs.
   * @returns A promise that resolves with a handle to the listener.
   */
  addListener(
    eventName: AudioRecordingEventName,
    callback: (data: { message: string }) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Start monitoring for recording interruptions
   */
  startMonitoring(): Promise<void>;

  /**
   * Stop monitoring for recording interruptions
   */
  stopMonitoring(): Promise<void>;

  /**
   * Remove all listeners
   */
  removeAllListeners(): Promise<void>;
}
