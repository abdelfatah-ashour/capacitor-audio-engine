import type { PluginListenerHandle } from '@capacitor/core';

export type PlaybackStatus = 'idle' | 'loading' | 'playing' | 'paused';

// Permission Status Enums
export enum PermissionStatus {
  /** Permission granted permanently */
  GRANTED = 'granted',
  /** Permission denied permanently */
  DENIED = 'denied',
  /** Permission denied permanently with "Don't ask again" (Android) */
  DENIED_PERMANENTLY = 'denied_permanently',
  /** Permission not yet requested from user */
  NOT_DETERMINED = 'not_determined',
  /** Permission granted only for current session (iOS 14+) */
  LIMITED = 'limited',
  /** Permission restricted by device policy/parental controls */
  RESTRICTED = 'restricted',
  /** Permission request in progress */
  REQUESTING = 'requesting',
  /** Permission unsupported on current platform/version */
  UNSUPPORTED = 'unsupported',
}

export enum AudioPermissionType {
  /** Microphone recording permission */
  MICROPHONE = 'microphone',
  /** Background notification permission */
  NOTIFICATIONS = 'notifications',
}

export type AudioRecordingEventName =
  | 'durationChange'
  | 'error'
  | 'waveLevel'
  | 'waveLevelInit'
  | 'waveLevelDestroy'
  | 'waveLevelError'
  | 'permissionStatusChanged'
  | 'recordingStatusChanged';
export type AudioPlaybackEventName = 'playbackStatusChanged' | 'playbackError' | 'playbackProgress';
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
  permissionStatusChanged: PermissionStatusChangedData;
  recordingStatusChanged: RecordingStatusChangedData;
};

export interface PlaybackStatusChangedData {
  status: PlaybackStatus;
  url: string;
  position: number;
}

export type AudioPlaybackEventMap = {
  playbackStatusChanged: PlaybackStatusChangedData;
  playbackError: PlaybackErrorData;
  playbackProgress: PlaybackProgressData;
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

export interface PermissionStatusChangedData {
  permissionType: AudioPermissionType;
  status: PermissionStatus;
  previousStatus?: PermissionStatus;
  message?: string;
}

// Simplified playback event data structures

export interface PlaybackStartedData {
  trackId: string;
  url: string;
}

export interface PlaybackPausedData {
  trackId: string;
  url: string;
  position: number;
}

export interface PlaybackStoppedData {
  trackId: string;
  url: string;
}

export interface PlaybackCompletedData {
  trackId: string;
  url: string;
}

export interface PlaybackErrorData {
  trackId: string;
  message: string;
}

export interface PlaybackProgressData {
  trackId: string;
  url: string;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
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

// Microphone utilities removed (recording-only)

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
}

export interface PreloadedTrackInfo {
  url: string;
  loaded: boolean;
  mimeType: string;
  duration: number;
  size: number;
}

export interface PreloadTracksResult {
  tracks: PreloadedTrackInfo[];
}

export interface PlaybackInfo {
  currentTrack: {
    id: string;
    url: string;
  } | null;
  currentIndex: number;
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}

export interface SeekTrackOptions {
  seconds: number;
  url?: string;
}

export interface PlayTrackOptions {
  url?: string;
}

export interface PauseTrackOptions {
  url?: string;
}

export interface ResumeTrackOptions {
  url?: string;
}

export interface StopTrackOptions {
  url?: string;
}

export interface SkipToIndexTrackOptions {
  index: number;
}

export interface TrimTrackOptions {
  /** URI or file path of the audio file to trim */
  uri: string;
  /** Start time in seconds */
  startTime: number;
  /** End time in seconds */
  endTime: number;
}

export interface TrimAudioResult {
  /** URI of the trimmed audio file */
  uri: string;
  /** Path of the trimmed audio file */
  path: string;
  /** Duration of the trimmed audio in seconds */
  duration: number;
}

export interface PermissionStatusResults {
  /** Overall permission status - granted only if all required permissions are granted */
  granted: boolean;
  /** Overall permission status */
  status: PermissionStatus;
}

export interface CheckPermissionResults {
  /** Permission type being checked */
  permissionType: AudioPermissionType;
  /** Current permission status */
  status: PermissionStatus;
}

export interface PermissionRequestOptions {
  /** Whether to show rationale before requesting permission (Android) */
  showRationale?: boolean;
  /** Custom rationale message to show user */
  rationaleMessage?: string;
  /** Whether to force request even if previously denied permanently */
  forceRequest?: boolean;
}

export type RecordingStatus = 'recording' | 'paused' | 'stopped' | 'idle';

export interface RecordingStatusChangedData {
  status: RecordingStatus;
}

export interface RecordingStatusInfo {
  status: RecordingStatus;
  duration: number;
  path?: string;
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
 * - Sample Rate: 48kHzkHz
 * - Channels: 1 (mono)
 * - Bitrate: 128kbps
 */
export interface CapacitorAudioEnginePlugin {
  /**
   * Check permission status with simplified result.
   * @returns Promise that resolves with simplified permission status including granted boolean and overall status
   * @platform web Returns unsupported status for all permissions
   * @platform android Uses ContextCompat.checkSelfPermission with simplified status mapping
   * @platform ios Uses AVAudioSession.recordPermission and UNUserNotificationCenter with simplified status mapping
   */
  checkPermissions(): Promise<PermissionStatusResults>;

  /**
   * Check microphone permission status with simplified information.
   * @returns Promise that resolves with simplified microphone permission status
   * @platform web Returns unsupported status
   * @platform android Uses ContextCompat.checkSelfPermission for RECORD_AUDIO
   * @platform ios Uses AVAudioSession.recordPermission with simplified status mapping
   */
  checkPermissionMicrophone(): Promise<PermissionStatusResults>;

  /**
   * Check notification permission status with simplified information.
   * @returns Promise that resolves with simplified notification permission status
   * @platform web Returns unsupported status
   * @platform android Uses ContextCompat.checkSelfPermission for POST_NOTIFICATIONS (Android 13+)
   * @platform ios Uses UNUserNotificationCenter with simplified status mapping
   */
  checkPermissionNotifications(): Promise<PermissionStatusResults>;

  /**
   * Request permissions with detailed options and status information.
   * @param options - Permission request options
   * @returns Promise that resolves with detailed permission status
   * @platform web Returns unsupported status for all permissions
   * @platform android Uses ActivityCompat.requestPermissions with detailed status handling
   * @platform ios Uses AVAudioSession.requestRecordPermission and UNUserNotificationCenter with detailed status handling
   */
  requestPermissions(options?: PermissionRequestOptions): Promise<PermissionStatusResults>;

  // Recording APIs removed (recording-only)

  /**
   * Reset the current live recording session without finalizing a file.
   * Behavior:
   * - Discards the current recording output/file and internal writer state
   * - Resets duration and waveform monitoring counters to 0
   * - Keeps recording session resources configured but puts the recording in paused state
   * - Allows `resumeRecording()` to continue recording fresh without re-calling `startRecording`
   * @returns Promise that resolves when the recording is reset
   */
  resetRecording(): Promise<void>;

  // Recording file APIs removed (use getAudioInfo for existing files)

  /**
   * Get detailed audio file information for a given URI or file path.
   * @param options - Object containing the file URI or path
   * @returns Promise resolving with `AudioFileInfo`
   */
  getAudioInfo(options: { uri: string }): Promise<AudioFileInfo>;

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

  // Microphone utilities removed

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
   * Preload audio tracks from URLs for individual playback
   * @param options - Preload options containing track URLs
   * @returns Promise that resolves with preload results for each track including load status, mimetype, duration, and file size
   * @platform web Uses HTML5 Audio API
   * @platform android Uses MediaPlayer for individual track management
   * @platform ios Uses AVPlayer for individual track management
   */
  preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult>;

  /**
   * Start or resume playback of current track or specific preloaded track by URL
   * @param options - Optional playback options with URL to play specific preloaded track
   * @returns Promise that resolves when playback starts
   */
  playTrack(options?: PlayTrackOptions): Promise<void>;

  /**
   * Pause audio playback for current track or specific preloaded track by URL
   * @param options - Optional pause options with URL to pause specific preloaded track
   * @returns Promise that resolves when playback is paused
   */
  pauseTrack(options?: PauseTrackOptions): Promise<void>;

  /**
   * Resume audio playback from paused state for current track or specific preloaded track by URL
   * @param options - Optional resume options with URL to resume specific preloaded track
   * @returns Promise that resolves when playback resumes
   */
  resumeTrack(options?: ResumeTrackOptions): Promise<void>;

  /**
   * Stop audio playback and reset to beginning for current track or specific preloaded track by URL
   * @param options - Optional stop options with URL to stop specific preloaded track
   * @returns Promise that resolves when playback stops
   */
  stopTrack(options?: StopTrackOptions): Promise<void>;

  /**
   * Seek to specific position in current track or specific preloaded track by URL
   * @param options - Seek options with time in seconds and optional URL for specific preloaded track
   * @returns Promise that resolves when seek completes
   */
  seekTrack(options: SeekTrackOptions): Promise<void>;

  /**
   * Skip to next track in playlist (simplified - no-op for single track playback)
   * @returns Promise that resolves when skip completes
   */
  skipToNext(): Promise<void>;

  /**
   * Skip to previous track in playlist (simplified - no-op for single track playback)
   * @returns Promise that resolves when skip completes
   */
  skipToPrevious(): Promise<void>;

  /**
   * Skip to specific track index in playlist (simplified - no-op for single track playback)
   * @param options - Options with target track index
   * @returns Promise that resolves when skip completes
   */
  skipToIndex(options: SkipToIndexTrackOptions): Promise<void>;

  /**
   * Get current playback information
   * @returns Promise that resolves with current playback state
   */
  getPlaybackInfo(): Promise<PlaybackInfo>;

  /**
   * Destroy and reinitialize the playback manager
   *
   * **Use Case:** Call this method when switching away from the playback tab to completely
   * clean up playback resources. This will:
   * - Stop any active playback
   * - Release all MediaPlayer instances
   * - Clear all preloaded tracks
   * - Abandon audio focus
   * - Reinitialize a fresh PlaybackManager instance
   *
   * **Important:** After calling this method, you will need to:
   * 1. Re-add event listeners for playback events
   * 2. Re-preload tracks if needed
   *
   * @returns Promise that resolves when playback manager is destroyed and reinitialized
   * @platform web No-op (browser handles cleanup automatically)
   * @platform android Releases and recreates PlaybackManager
   * @platform ios Releases and recreates AVPlayer instances
   *
   * @example
   * ```typescript
   * // When leaving playback tab
   * async selectTab(tab: string) {
   *   if (previousTab === 'playback' && tab !== 'playback') {
   *     // Clean up playback resources
   *     await CapacitorAudioEngine.destroyPlayback();
   *   }
   *
   *   if (tab === 'playback') {
   *     // Re-setup listeners and preload tracks
   *     await this.setupPlaybackListeners();
   *     await this.preloadTracks();
   *   }
   * }
   * ```
   */
  destroyPlayback(): Promise<void>;

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

  /**
   * Start live recording capture from microphone.
   * @returns Promise that resolves with the recording URI
   */
  startRecording(options: { path: string }): Promise<{ uri: string }>;

  /**
   * Stop live recording capture and get file information.
   * @returns Promise that resolves with complete audio file information
   */
  stopRecording(): Promise<AudioFileInfo>;

  /**
   * Manually pause live recording capture.
   */
  pauseRecording(): Promise<void>;

  /**
   * Manually resume live recording capture.
   */
  resumeRecording(): Promise<void>;

  /**
   * Get current recording status information including state, duration, and output path.
   * @returns Promise that resolves with current recording status
   */
  getRecordingStatus(): Promise<RecordingStatusInfo>;

  /**
   * Trim an audio file to a specific time range.
   * This method creates a new trimmed audio file from the original source file.
   * The original file is not modified.
   *
   * @param options - Trim options containing file URI, start time, and end time in seconds
   * @returns Promise that resolves with complete audio file information
   * @throws {Error} If the file doesn't exist, times are invalid, or trimming fails
   * @platform web Not fully supported - basic implementation using Web Audio API
   * @platform android Uses MediaCodec and MediaMuxer for efficient audio trimming
   * @platform ios Uses AVAssetExportSession for efficient audio trimming
   *
   * @example
   * ```typescript
   * // Trim audio file from 5 seconds to 30 seconds
   * const result = await CapacitorAudioEngine.trimAudio({
   *   uri: 'file:///path/to/audio.m4a',
   *   startTime: 5.0,
   *   endTime: 30.0
   * });
   * console.log('Trimmed file:', result.uri);
   * console.log('Duration:', result.duration);
   * ```
   */
  trimAudio(options: TrimTrackOptions): Promise<AudioFileInfo>;
}
