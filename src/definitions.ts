import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped';
export type AudioRecordingEventName =
  | 'durationChange'
  | 'error'
  | 'waveLevel'
  | 'waveLevelInit'
  | 'waveLevelDestroy'
  | 'waveLevelError';
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
  waveLevel: WaveLevelData;
  waveLevelInit: WaveLevelInitData;
  waveLevelDestroy: WaveLevelDestroyData;
  waveLevelError: ErrorEventData;
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

export interface WaveLevelData {
  level: number;
  timestamp: number;
}

export interface WaveLevelInitData {
  status: string;
  emissionInterval: number;
  sampleRate: number;
}

export interface WaveLevelDestroyData {
  status: string;
  reason: string;
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

// Recording Configuration Enums
export enum AudioSampleRate {
  /** Low quality - 8kHz for voice recording */
  VOICE_8K = 8000,
  /** Voice quality - 16kHz for speech */
  VOICE_16K = 16000,
  /** Standard quality - 22.05kHz (default optimized) */
  STANDARD_22K = 22050,
  /** CD quality - 44.1kHz */
  CD_44K = 44100,
  /** High quality - 48kHz */
  HIGH_48K = 48000,
}

export enum AudioChannels {
  /** Mono - single channel */
  MONO = 1,
  /** Stereo - two channels */
  STEREO = 2,
}

export enum AudioBitrate {
  /** Very low bitrate - 16kbps for voice notes */
  VERY_LOW = 16000,
  /** Low bitrate - 32kbps for voice recording */
  LOW = 32000,
  /** Medium bitrate - 64kbps (default optimized) */
  MEDIUM = 64000,
  /** High bitrate - 128kbps for music */
  HIGH = 128000,
  /** Very high bitrate - 256kbps for high quality */
  VERY_HIGH = 256000,
}

export interface RecordingOptions {
  /**
   * Audio sample rate (Hz). Default: AudioSampleRate.STANDARD_22K (optimized for smaller file sizes)
   */
  sampleRate?: AudioSampleRate | number;
  /**
   * Number of audio channels. Default: AudioChannels.MONO
   */
  channels?: AudioChannels | number;
  /**
   * Audio bitrate (bps). Default: AudioBitrate.MEDIUM (optimized for smaller file sizes)
   */
  bitrate?: AudioBitrate | number;

  /**
   * Maximum recording duration in seconds.
   * When set, enables segment rolling mode:
   * - Records in multi-minute segments (2–5 minutes each, default 5 minutes)
   * - Maintains a rolling buffer based on the specified max duration
   * - Automatically finalizes prior segments; stop only closes the current segment for minimal latency
   * If not set, segment rolling still improves performance by recording in segments, and the final file will include the full session.
   */
  maxDuration?: number;
  /**
   * Note: The audio format is always .m4a (MPEG-4/AAC) on all platforms.
   *
   * Enhanced Recording Features:
   * - Automatic segment rolling (multi-minute segments, default 5 minutes) for improved reliability and minimal stop latency
   * - Rolling window retention based on maxDuration for efficient memory usage
   * - Automatic segment merging when needed (most stops only close the current segment)
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

// Wave Level Configuration Enums
export enum WaveLevelEmissionInterval {
  /** Real-time emission (50ms) - Minimum allowed */
  REALTIME = 50,
  /** Very fast updates (100ms) */
  VERY_FAST = 100,
  /** Fast updates (200ms) */
  FAST = 200,
  /** Medium updates (500ms) - Maximum allowed */
  MEDIUM = 500,
  /** Default emission (1000ms) - As per SRS */
  DEFAULT = 1000,
}

/**
 * Simplified wave level configuration options
 */
export interface WaveLevelConfiguration {
  /** Emission interval in milliseconds (50-500ms, default: 1000ms) */
  emissionInterval?: WaveLevelEmissionInterval | number;
}

/**
 * Result of wave level configuration
 */
export interface WaveLevelConfigurationResult {
  success: boolean;
  configuration: {
    emissionInterval: number;
  };
}

export interface PreloadTracksOptions {
  tracks: string[];
  preloadNext?: boolean;
}

export interface PreloadedTrackInfo {
  url: string;
  loaded: boolean;
  mimeType?: string;
  duration?: number;
  size?: number;
}

export interface PreloadTracksResult {
  tracks: PreloadedTrackInfo[];
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
  url?: string;
}

export interface PlayAudioOptions {
  url?: string;
}

export interface PauseAudioOptions {
  url?: string;
}

export interface ResumeAudioOptions {
  url?: string;
}

export interface StopAudioOptions {
  url?: string;
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
  checkPermissions(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;

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
  requestPermissions(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }>;

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
   * Reset the current recording session without finalizing a file.
   * Behavior:
   * - Discards the current recording (segments are cleared)
   * - Discards current duration and waveform data
   * - Keeps the previously configured recording settings for seamless resume
   * - Leaves the session in paused state so resumeRecording() starts fresh
   * @returns Promise that resolves when the session is reset
   */
  resetRecording(): Promise<void>;

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

  /**
   * Configure wave level monitoring for real-time audio level emission.
   * This method sets up real-time wave level collection that emits normalized
   * audio levels (0.0-1.0) at configurable intervals during recording.
   *
   * @returns Promise that resolves with wave level configuration result
   * @throws {Error} If configuration fails
   * @platform web Not supported
   * @platform android Configures real-time PCM audio processing with RMS calculation
   * @platform ios Configures AVAudioEngine audio tap with RMS calculation
   *
   * @example
   * ```typescript
   * // Configure wave level monitoring with default settings (1000ms intervals)
   * await CapacitorAudioEngine.configureWaveform();
   *
   * // Listen for wave level events during recording
   * const waveLevelListener = await CapacitorAudioEngine.addListener('waveLevel', (data) => {
   *   console.log('Audio level:', data.level); // Normalized value (0.0-1.0)
   *   console.log('Timestamp:', data.timestamp); // Timestamp in milliseconds
   * });
   * ```
   */
  /**
   * Configure wave level monitoring for real-time audio level emission.
   * This method sets up real-time wave level collection that emits normalized
   * audio levels (0.0-1.0) at configurable intervals during recording.
   *
   * @param options - Optional configuration object
   * @param options.EmissionInterval - Emission interval in milliseconds (50-500ms, default: 1000ms)
   * @returns Promise that resolves with wave level configuration result
   * @throws {Error} If configuration fails
   * @platform web Not supported
   * @platform android Configures real-time PCM audio processing with RMS calculation
   * @platform ios Configures AVAudioEngine audio tap with RMS calculation
   *
   * @example
   * ```typescript
   * // Configure wave level monitoring with 200ms intervals
   * await CapacitorAudioEngine.configureWaveform({ EmissionInterval: 200 });
   * ```
   */
  configureWaveform(options?: { EmissionInterval?: number }): Promise<WaveLevelConfigurationResult>;

  /**
   * Destroy wave level configuration and clean up resources.
   * This will stop wave level monitoring if active and reset configuration to defaults.
   * @returns Promise that resolves when wave level configuration is destroyed
   * @platform web Not supported
   * @platform android Stops wave level monitoring and releases AudioRecord resources
   * @platform ios Stops AVAudioEngine tap and cleans up resources
   *
   * @example
   * ```typescript
   * // Destroy wave level configuration when no longer needed
   * await CapacitorAudioEngine.destroyWaveform();
   * ```
   */
  destroyWaveform(): Promise<void>;

  // ==================== AUDIO PLAYBACK METHODS ====================

  /**
   * Preload audio tracks from URLs and initialize playlist
   * @param options - Preload options containing track URLs and preload settings
   * @returns Promise that resolves with preload results for each track including load status, mimetype, duration, and file size
   * @platform web Uses HTML5 Audio API
   * @platform android Uses ExoPlayer with ConcatenatingMediaSource
   * @platform ios Uses AVQueuePlayer or AVPlayer with queue management
   */
  preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult>;

  /**
   * Start or resume playback of current track or specific preloaded track by URL
   * @param options - Optional playback options with URL to play specific preloaded track
   * @returns Promise that resolves when playback starts
   */
  playAudio(options?: PlayAudioOptions): Promise<void>;

  /**
   * Pause audio playback for current track or specific preloaded track by URL
   * @param options - Optional pause options with URL to pause specific preloaded track
   * @returns Promise that resolves when playback is paused
   */
  pauseAudio(options?: PauseAudioOptions): Promise<void>;

  /**
   * Resume audio playback from paused state for current track or specific preloaded track by URL
   * @param options - Optional resume options with URL to resume specific preloaded track
   * @returns Promise that resolves when playback resumes
   */
  resumeAudio(options?: ResumeAudioOptions): Promise<void>;

  /**
   * Stop audio playback and reset to beginning for current track or specific preloaded track by URL
   * @param options - Optional stop options with URL to stop specific preloaded track
   * @returns Promise that resolves when playback stops
   */
  stopAudio(options?: StopAudioOptions): Promise<void>;

  /**
   * Seek to specific position in current track or specific preloaded track by URL
   * @param options - Seek options with time in seconds and optional URL for specific preloaded track
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

  /**
   * Navigate to the app's permission settings screen.
   * This method opens the system settings page where users can manually adjust permissions.
   * Useful when permissions are denied and need to be changed through settings.
   * @returns Promise that resolves when navigation is initiated
   * @throws {Error} If navigation fails
   * @platform web Not supported - shows alert with instructions
   * @platform android Opens the app-specific settings page (App Info) using ACTION_APPLICATION_DETAILS_SETTINGS intent, where users can manage permissions, notifications, and other app settings
   * @platform ios Opens the app-specific settings page using UIApplication.openSettingsURLString, which navigates directly to Settings > [App Name] where users can manage app permissions and settings
   */
  openSettings(): Promise<void>;
}
