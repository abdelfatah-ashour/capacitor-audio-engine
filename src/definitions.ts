import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type PlaybackStatus = 'idle' | 'loaded' | 'playing' | 'paused' | 'stopped' | 'completed' | 'error';
export type AudioRecordingEventName = 'recordingInterruption' | 'durationChange' | 'error';
export type AudioPlaybackEventName =
  | 'playbackStatusChange'
  | 'playbackProgress'
  | 'playbackCompleted'
  | 'playbackError';
export type AudioEventName = AudioRecordingEventName | AudioPlaybackEventName;

export interface AudioRecordingEvent<T = any> {
  eventName: AudioRecordingEventName;
  payload: T;
}

export interface AudioPlaybackEvent<T = any> {
  eventName: AudioPlaybackEventName;
  payload: T;
}

export type AudioRecordingEventMap = {
  recordingInterruption: RecordingInterruptionData;
  durationChange: DurationChangeData;
  error: ErrorEventData;
};

export type AudioPlaybackEventMap = {
  playbackStatusChange: PlaybackStatusData;
  playbackProgress: PlaybackProgressData;
  playbackCompleted: PlaybackCompletedData;
  playbackError: PlaybackErrorData;
};

export type AudioEventMap = AudioRecordingEventMap & AudioPlaybackEventMap;

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

export interface MicrophoneInfo {
  id: number;
  name: string;
  type: 'internal' | 'external' | 'unknown';
  description?: string;
  uid?: string; // iOS only
  isConnected?: boolean; // Android only
}

export interface MicrophoneStatusResult {
  busy: boolean;
  reason?: string;
}

export interface AvailableMicrophonesResult {
  microphones: MicrophoneInfo[];
}

export interface SwitchMicrophoneOptions {
  microphoneId: number;
}

export interface SwitchMicrophoneResult {
  success: boolean;
  microphoneId: number;
}

export interface PlaybackOptions {
  /**
   * Playback speed (0.5 - 2.0). Default: 1.0
   */
  speed?: number;
  /**
   * Start time in seconds. Default: 0
   */
  startTime?: number;
  /**
   * Whether to loop the audio. Default: false
   */
  loop?: boolean;
  /**
   * Volume level (0.0 - 1.0). Default: 1.0
   */
  volume?: number;
}

export interface PreloadOptions {
  /**
   * URI of the audio file to preload
   */
  uri: string;
  /**
   * Whether to prepare for playback immediately. Default: true
   */
  prepare?: boolean;
}

export interface AudioPlayerInfo {
  status: PlaybackStatus;
  currentTime: number;
  duration: number;
  speed?: number;
  volume?: number;
  isLooping?: boolean;
  uri?: string;
}

export interface PlaybackProgressData {
  currentTime: number;
  duration: number;
  position: number; // Playback position as percentage (0-100)
}

export interface PlaybackStatusData {
  status: PlaybackStatus;
  currentTime?: number;
  duration?: number;
}

export interface PlaybackErrorData {
  message: string;
  code?: string | number;
  details?: any;
}

export interface PlaybackCompletedData {
  duration: number;
}

/**
 * Interface for the Native Audio Plugin that provides audio recording and playback capabilities.
 *
 * Platform-specific implementations:
 * - Web: Uses MediaRecorder API with WebM/Opus format for recording, and AudioContext for playback
 * - Android: Uses MediaRecorder with AAC format in MP4 container for recording, and Android MediaPlayer for playback
 * - iOS: Uses AVAudioRecorder with AAC format in M4A container for recording, and AVAudioPlayer for playback
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
   * @property {boolean} audioPermission - Whether audio permission is granted
   * @property {boolean} notificationPermission - Whether notification permission is granted (Android 13+ only)
   * @platform web Uses navigator.permissions.query API
   * @platform android Uses ContextCompat.checkSelfPermission with RECORD_AUDIO permission
   * @platform ios Uses AVAudioSession.recordPermission
   */
  checkPermission(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;

  /**
   * Request microphone permission from the user.
   * @returns Promise that resolves with an object containing the permission status
   * @property {boolean} granted - Whether microphone permission was granted
   * @property {boolean} audioPermission - Whether audio permission was granted
   * @property {boolean} notificationPermission - Whether notification permission was granted (Android 13+ only)
   * @platform web Uses navigator.mediaDevices.getUserMedia API
   * @platform android Uses ActivityCompat.requestPermissions with RECORD_AUDIO permission
   * @platform ios Uses AVAudioSession.requestRecordPermission
   */
  requestPermission(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;

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
   * Add a listener for recording or playback events
   * @param eventName - The name of the event to listen to
   * @param callback - The callback to invoke when the event occurs
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported for playback events, recording events use MediaRecorder
   * @platform android Uses MediaRecorder events and MediaPlayer events
   * @platform ios Uses AVAudioSession notifications and AVAudioPlayer notifications
   */
  addListener<T extends AudioEventName>(
    eventName: T,
    callback: (event: AudioEventMap[T]) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners
   * @returns Promise that resolves when all listeners are removed
   */
  removeAllListeners(): Promise<void>;

  /**
   * Check if microphone is currently being used by another application.
   * @returns Promise that resolves with microphone status
   * @property {boolean} busy - Whether microphone is currently in use
   * @platform web Not supported
   * @platform android Uses AudioRecord to test microphone availability
   * @platform ios Uses AVAudioSession to check audio state
   */
  isMicrophoneBusy(): Promise<MicrophoneStatusResult>;

  /**
   * Get list of available microphones (internal and external).
   * @returns Promise that resolves with available microphones
   * @property {MicrophoneInfo[]} microphones - Array of available microphones
   * @platform web Not supported
   * @platform android Uses AudioManager.getDevices() to enumerate audio inputs
   * @platform ios Uses AVAudioSession.availableInputs to list audio inputs
   */
  getAvailableMicrophones(): Promise<AvailableMicrophonesResult>;

  /**
   * Switch between microphones while keeping recording active.
   * @param options - Switch microphone options
   * @param options.microphoneId - ID of the microphone to switch to
   * @returns Promise that resolves with switch result
   * @throws {Error} If microphone ID is invalid
   * @throws {Error} If switching fails
   * @platform web Not supported
   * @platform android Uses AudioRecord.setPreferredDevice() to switch input
   * @platform ios Uses AVAudioSession.setPreferredInput() to switch input
   */
  switchMicrophone(options: SwitchMicrophoneOptions): Promise<SwitchMicrophoneResult>;

  /**
   * Start audio playback.
   * @param options - Playback options
   * @param options.uri - URI of the audio file to play
   * @param options.speed - Playback speed (0.5 - 2.0). Default: 1.0
   * @param options.startTime - Start time in seconds. Default: 0
   * @param options.loop - Whether to loop the audio. Default: false
   * @param options.volume - Volume level (0.0 - 1.0). Default: 1.0
   * @returns Promise that resolves when playback starts
   * @throws {Error} If playback is already in progress
   * @throws {Error} If audio session setup fails
   * @platform web Uses AudioContext and MediaElementAudioSourceNode
   * @platform android Uses Android MediaPlayer
   * @platform ios Uses AVAudioPlayer
   */
  startPlayback(options: PlaybackOptions & { uri: string }): Promise<void>;

  /**
   * Pause the current playback.
   * @returns Promise that resolves when playback is paused
   * @throws {Error} If no active playback exists or if playback is already paused
   * @platform web Uses AudioContext.suspend()
   * @platform android Uses MediaPlayer.pause()
   * @platform ios Uses AVAudioPlayer.pause()
   */
  pausePlayback(): Promise<void>;

  /**
   * Resume the current playback if it was previously paused.
   * @returns Promise that resolves when playback is resumed
   * @throws {Error} If no active playback exists or if playback is not paused
   * @platform web Uses AudioContext.resume()
   * @platform android Uses MediaPlayer.start()
   * @platform ios Uses AVAudioPlayer.play()
   */
  resumePlayback(): Promise<void>;

  /**
   * Stop the current playback.
   * @returns Promise that resolves when playback is stopped
   * @throws {Error} If no active playback exists
   * @platform web Uses AudioContext.close()
   * @platform android Uses MediaPlayer.stop()
   * @platform ios Uses AVAudioPlayer.stop()
   */
  stopPlayback(): Promise<void>;

  /**
   * Seek to a specific time in the currently playing audio.
   * @param options - Seek options
   * @param options.time - Time in seconds to seek to
   * @returns Promise that resolves when seek is complete
   * @throws {Error} If no active playback exists
   * @platform web Uses AudioContext.currentTime
   * @platform android Uses MediaPlayer.seekTo()
   * @platform ios Uses AVAudioPlayer.setCurrentTime()
   */
  seekTo(options: { time: number }): Promise<void>;

  /**
   * Get the current playback status.
   * @returns Promise that resolves with the current playback status
   * @property {PlaybackStatus} status - The current state of the player
   * @property {number} currentTime - The current playback position in seconds
   * @property {number} duration - The total duration of the audio in seconds
   * @property {number} speed - The current playback speed
   * @property {number} volume - The current volume level
   * @property {boolean} isLooping - Whether the audio is looping
   * @property {string} uri - The URI of the current audio file
   */
  getPlaybackStatus(): Promise<AudioPlayerInfo>;

  /**
   * Add a listener for playback events
   * @param eventName - The name of the event to listen to
   * @param callback - The callback to invoke when the event occurs
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported
   * @platform android Uses MediaPlayer events
   * @platform ios Uses AVAudioPlayer notifications
   */
  addPlaybackListener(eventName: AudioPlaybackEventName, callback: (event: any) => void): Promise<PluginListenerHandle>;

  /**
   * Remove all playback listeners
   * @returns Promise that resolves when all listeners are removed
   */
  removeAllPlaybackListeners(): Promise<void>;

  /**
   * Preload an audio file for faster playback start.
   * @param options - Preload options
   * @param options.uri - URI of the audio file to preload
   * @param options.prepare - Whether to prepare for playback immediately. Default: true
   * @returns Promise that resolves when preloading is complete
   * @throws {Error} If preloading fails
   * @platform web Not supported
   * @platform android Uses MediaPlayer.prepareAsync()
   * @platform ios Uses AVAudioPlayer.prepareToPlay()
   */
  preload(options: PreloadOptions): Promise<void>;
}
