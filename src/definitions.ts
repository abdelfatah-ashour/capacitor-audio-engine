import type { PluginListenerHandle } from '@capacitor/core';

export type RecordingStatus = 'idle' | 'recording' | 'paused';
export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused' | 'stopped';
export type AudioRecordingEventName = 'durationChange' | 'error' | 'waveformData' | 'waveformInit' | 'waveformDestroy';
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
  waveformData: WaveformData;
  waveformInit: WaveformInitData;
  waveformDestroy: WaveformDestroyData;
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

export interface WaveformData {
  level: number;
  timestamp: number;
}

export interface WaveformInitData {
  numberOfBars: number;
  speechOnlyMode?: boolean;
  speechThreshold?: number;
  vadEnabled?: boolean;
  calibrationDuration?: number;
}

export interface WaveformDestroyData {
  reason: string;
  timestamp: number;
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

// Enums for configuration parameters
export enum WaveformBarsCount {
  BARS_16 = 16,
  BARS_32 = 32,
  BARS_64 = 64,
  BARS_128 = 128,
  BARS_256 = 256,
}

export enum WaveformDebounceTime {
  /** Real-time visualization (20ms) */
  REALTIME = 0.02,
  /** Very fast updates (50ms) */
  VERY_FAST = 0.05,
  /** Fast updates (100ms) */
  FAST = 0.1,
  /** Medium updates (250ms) */
  MEDIUM = 0.25,
  /** Slow updates (500ms) */
  SLOW = 0.5,
  /** Very slow updates (1000ms) - Default */
  VERY_SLOW = 1.0,
}

export enum SpeechThreshold {
  /** Very sensitive (0.005) */
  VERY_SENSITIVE = 0.005,
  /** Sensitive (0.01) */
  SENSITIVE = 0.01,
  /** Normal (0.02) */
  NORMAL = 0.02,
  /** Moderate (0.04) */
  MODERATE = 0.04,
  /** Less sensitive (0.06) */
  LESS_SENSITIVE = 0.06,
  /** Not sensitive (0.1) */
  NOT_SENSITIVE = 0.1,
}

export enum VADWindowSize {
  /** Minimum latency (~150ms) */
  MINIMAL = 3,
  /** Low latency (~200ms) */
  LOW = 4,
  /** Normal latency (~250ms) */
  NORMAL = 5,
  /** Medium latency (~400ms) */
  MEDIUM = 8,
  /** High accuracy (~500ms) */
  HIGH = 10,
  /** Maximum accuracy (~750ms) */
  MAXIMUM = 15,
}

export enum CalibrationDuration {
  /** Quick calibration (500ms) */
  QUICK = 500,
  /** Normal calibration (1000ms) */
  NORMAL = 1000,
  /** Extended calibration (2000ms) */
  EXTENDED = 2000,
  /** Long calibration (3000ms) */
  LONG = 3000,
}

export enum GainFactor {
  /** Minimal gain (5.0) */
  MINIMAL = 5.0,
  /** Low gain (10.0) */
  LOW = 10.0,
  /** Standard gain (15.0) */
  STANDARD = 15.0,
  /** Medium gain (20.0) - Default */
  MEDIUM = 20.0,
  /** High gain (30.0) */
  HIGH = 30.0,
  /** Maximum gain (50.0) */
  MAXIMUM = 50.0,
}

/**
 * Unified waveform configuration options combining all waveform features
 */
export interface WaveformConfiguration {
  /** Number of bars in the waveform visualization (default: 128) */
  numberOfBars?: WaveformBarsCount | number;
  /** Debounce time in seconds between waveform data emissions (default: 1.0) */
  debounceTime?: WaveformDebounceTime | number;

  /** Speech detection configuration */
  speechDetection?: {
    /** Enable speech-only detection */
    enabled: boolean;
    /** Speech detection sensitivity threshold */
    threshold?: SpeechThreshold | number;
    /** Background noise calibration duration */
    calibrationDuration?: CalibrationDuration | number;
  };

  /** Voice Activity Detection (VAD) configuration */
  vad?: {
    /** Enable VAD for improved speech detection */
    enabled: boolean;
    /** VAD analysis window size (affects latency vs accuracy) */
    windowSize?: VADWindowSize | number;
    /** Enable human voice band filtering (85Hz-3400Hz) */
    enableVoiceFilter?: boolean;
  };
}

/**
 * Result of waveform configuration
 */
export interface WaveformConfigurationResult {
  success: boolean;
  configuration: {
    numberOfBars: number;
    debounceTimeMs: number;
    speechDetection: {
      enabled: boolean;
      threshold: number;
      calibrationDuration: number;
    };
    vad: {
      enabled: boolean;
      windowSize: number;
      estimatedLatencyMs: number;
      enableVoiceFilter: boolean;
    };
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
   * Configure waveform visualization and monitoring settings with default values.
   * This method sets up real-time waveform data collection using quality-aware
   * defaults based on current recording configuration.
   *
   * @returns Promise that resolves with complete configuration result
   * @throws {Error} If configuration fails
   * @platform web Not supported
   * @platform android Configures real-time PCM audio processing with speech detection and VAD
   * @platform ios Configures AVAudioEngine audio tap with speech detection and VAD
   *
   * @example
   * ```typescript
   * // Configure waveform with default settings
   * await CapacitorAudioEngine.configureWaveform();
   *
   * // Listen for waveform data events during recording
   * const waveformListener = await CapacitorAudioEngine.addListener('waveformData', (data) => {
   *   console.log('Amplitude level:', data.level); // Single normalized value (0-1)
   * });
   * ```
   */
  configureWaveform(): Promise<WaveformConfigurationResult>;

  /**
   * Destroy waveform configuration and clean up resources.
   * This will stop waveform monitoring if active and reset configuration to defaults.
   * @returns Promise that resolves when waveform configuration is destroyed
   * @platform web Not supported
   * @platform android Stops waveform monitoring and releases AudioRecord resources
   * @platform ios Stops AVAudioEngine tap and cleans up resources
   *
   * @example
   * ```typescript
   * // Destroy waveform configuration when no longer needed
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
