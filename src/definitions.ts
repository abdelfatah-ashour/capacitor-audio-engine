import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped';
export type AudioRecordingEventName = 'durationChange' | 'error';
export type AudioPlaybackEventName =
  | 'trackChanged'
  | 'trackEnded'
  | 'playbackStarted'
  | 'playbackPaused'
  | 'playbackError'
  | 'playbackProgress'
  | 'playbackStatusChanged';
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
  durationChange: DurationChangeData;
  error: ErrorEventData;
};

export type AudioPlaybackEventMap = {
  trackChanged: TrackChangedData;
  trackEnded: TrackEndedData;
  playbackStarted: PlaybackStartedData;
  playbackPaused: PlaybackPausedData;
  playbackError: ErrorEventData;
  playbackProgress: PlaybackProgressData;
  playbackStatusChanged: PlaybackStatusChangedData;
};

export type AudioEventMap = AudioRecordingEventMap & AudioPlaybackEventMap;

export interface DurationChangeData {
  duration: number;
}

export interface ErrorEventData {
  message: string;
  code?: string | number;
  details?: any;
}

export interface AudioTrack {
  id: string;
  url: string;
  title?: string;
  artist?: string;
  artworkUrl?: string;
}

export interface TrackChangedData {
  track: AudioTrack;
  index: number;
}

export interface TrackEndedData {
  track: AudioTrack;
  index: number;
}

export interface PlaybackStartedData {
  track: AudioTrack;
  index: number;
}

export interface PlaybackPausedData {
  track: AudioTrack;
  index: number;
  position: number;
}

export interface PlaybackProgressData {
  track: AudioTrack;
  index: number;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}

export interface PlaybackStatusChangedData {
  track: AudioTrack | null;
  index: number;
  status: PlaybackStatus;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}

export interface RecordingOptions {
  /**
   * Audio sample rate (Hz). Default: 22050 (optimized for smaller file sizes)
   */
  sampleRate?: number;
  /**
   * Number of audio channels. Default: 1 (mono)
   */
  channels?: number;
  /**
   * Audio bitrate (bps). Default: 64000 (optimized for smaller file sizes)
   */
  bitrate?: number;
  /**
   * Audio quality preset. If specified, overrides individual sampleRate and bitrate settings.
   * - 'low': 16kHz, 32kbps - smallest files, suitable for voice notes
   * - 'medium': 22.05kHz, 64kbps - balanced quality/size (default)
   * - 'high': 44.1kHz, 128kbps - higher quality, larger files
   */
  quality?: 'low' | 'medium' | 'high';
  /**
   * Maximum recording duration in seconds.
   * When set, enables segment rolling mode:
   * - Records in 30-second segments
   * - Maintains rolling buffer of last 10 minutes (20 segments)
   * - Automatically merges segments when recording stops
   * If not set, uses linear recording mode.
   */
  maxDuration?: number;
  /**
   * Note: The audio format is always .m4a (MPEG-4/AAC) on all platforms.
   *
   * Enhanced Recording Features:
   * - Automatic segment rolling (30-second segments) for improved reliability
   * - Rolling window retention (10 minutes max) for efficient memory usage
   * - Automatic segment merging when recording stops
   * - Better handling of long recording sessions and interruptions
   */
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
  /**
   * Base64-encoded audio data with MIME prefix (Data URI format)
   *
   * iOS may return compressed data for better performance:
   * - Compressed: "data:audio/m4a;base64,lzfse:<compressed-base64-data>"
   * - Uncompressed: "data:audio/m4a;base64,<base64-data>"
   *
   * Use compression-utils.ts helpers to parse and handle compressed data.
   * Compression is lossless LZFSE algorithm optimized for iOS.
   */
  base64?: string;
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

export interface PlaylistOptions {
  tracks: AudioTrack[];
  preloadNext?: boolean;
}

export interface PlaybackInfo {
  currentTrack: AudioTrack | null;
  currentIndex: number;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
  status: PlaybackStatus;
}

export interface SeekOptions {
  seconds: number;
}

export interface SkipToIndexOptions {
  index: number;
}

/**
 * Interface for the Native Audio Plugin that provides audio recording capabilities.
 *
 * Platform-specific implementations:
 * - Web: Uses MediaRecorder API with WebM/Opus format for recording
 * - Android: Uses MediaRecorder with AAC format in MP4 container for recording
 * - iOS: Uses AVAudioRecorder with AAC format in M4A container for recording
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
   * @property {number} duration - The current recording duration in seconds
   */
  getStatus(): Promise<{
    status: RecordingStatus;
    isRecording: boolean;
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
   * @platform web Not supported, recording events use MediaRecorder
   * @platform android Uses MediaRecorder events
   * @platform ios Uses AVAudioSession notifications
   *
   * @example
   * ```typescript
   * // Listen for playback progress updates (every 500ms)
   * const progressListener = await CapacitorAudioEngine.addListener('playbackProgress', (data) => {
   *   console.log(`Progress: ${data.currentPosition}/${data.duration} seconds`);
   * });
   *
   * // Listen for status changes
   * const statusListener = await CapacitorAudioEngine.addListener('playbackStatusChanged', (data) => {
   *   console.log(`Status: ${data.status}, Playing: ${data.isPlaying}`);
   * });
   *
   * // Don't forget to remove listeners when done
   * progressListener.remove();
   * statusListener.remove();
   * ```
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

  // ==================== AUDIO PLAYBACK METHODS ====================

  /**
   * Initialize playlist with audio tracks and preload first track
   * @param options - Playlist options containing tracks and preload settings
   * @returns Promise that resolves when playlist is initialized
   * @platform web Uses HTML5 Audio API
   * @platform android Uses ExoPlayer with ConcatenatingMediaSource
   * @platform ios Uses AVQueuePlayer or AVPlayer with queue management
   */
  initPlaylist(options: PlaylistOptions): Promise<void>;

  /**
   * Start or resume playback of current track
   * @returns Promise that resolves when playback starts
   */
  playAudio(): Promise<void>;

  /**
   * Pause audio playback
   * @returns Promise that resolves when playback is paused
   */
  pauseAudio(): Promise<void>;

  /**
   * Resume audio playback from paused state
   * @returns Promise that resolves when playback resumes
   */
  resumeAudio(): Promise<void>;

  /**
   * Stop audio playback and reset to beginning
   * @returns Promise that resolves when playback stops
   */
  stopAudio(): Promise<void>;

  /**
   * Seek to specific position in current track
   * @param options - Seek options with time in seconds
   * @returns Promise that resolves when seek completes
   */
  seekAudio(options: SeekOptions): Promise<void>;

  /**
   * Skip to next track in playlist
   * @returns Promise that resolves when skip completes
   */
  skipToNext(): Promise<void>;

  /**
   * Skip to previous track in playlist
   * @returns Promise that resolves when skip completes
   */
  skipToPrevious(): Promise<void>;

  /**
   * Skip to specific track index in playlist
   * @param options - Options with target track index
   * @returns Promise that resolves when skip completes
   */
  skipToIndex(options: SkipToIndexOptions): Promise<void>;

  /**
   * Get current playback information
   * @returns Promise that resolves with current playback state
   */
  getPlaybackInfo(): Promise<PlaybackInfo>;
}
