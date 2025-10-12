import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorAudioEnginePlugin,
  AudioEventName,
  AudioFileInfo,
  AudioEventMap,
  PreloadTracksOptions,
  PreloadTracksResult,
  PlaybackInfo,
  SeekTrackOptions,
  SkipToIndexTrackOptions,
  WaveLevelConfigurationResult,
  PermissionRequestOptions,
  PermissionStatusResults,
  RecordingStatusInfo,
  TrimTrackOptions,
} from './definitions';
import { PermissionStatus } from './definitions';

declare global {
  interface BlobEventInit extends EventInit {
    data: Blob;
  }

  interface BlobEvent extends Event {
    readonly data: Blob;
  }
}

export class CapacitorAudioEngineWeb extends WebPlugin implements CapacitorAudioEnginePlugin {
  /**
   * Reset the current live recording session.
   * @platform web Not supported
   */
  async resetRecording(): Promise<void> {
    console.warn(
      'resetRecording is not supported on web platform. For web implementation, consider using MediaRecorder or WebAudio directly.',
    );
    throw new Error('resetRecording is not supported on web platform');
  }

  /**
   * Check permission status with detailed information for each permission type.
   * @returns Promise that resolves with detailed permission status including granular information
   * @platform web Returns unsupported status for all permissions
   */
  async checkPermissions(): Promise<PermissionStatusResults> {
    console.warn(
      'checkPermissions is not fully supported on web platform. For web implementation, consider using navigator.permissions.query API directly.',
    );

    return {
      granted: false,
      status: PermissionStatus.UNSUPPORTED,
    };
  }

  /**
   * Check microphone permission status with detailed information.
   * @returns Promise that resolves with detailed microphone permission status
   * @platform web Returns unsupported status
   */
  async checkPermissionMicrophone(): Promise<PermissionStatusResults> {
    console.warn(
      'checkPermissionMicrophone is not fully supported on web platform. For web implementation, consider using navigator.permissions.query API directly.',
    );

    return {
      granted: false,
      status: PermissionStatus.UNSUPPORTED,
    };
  }

  /**
   * Check notification permission status with detailed information.
   * @returns Promise that resolves with detailed notification permission status
   * @platform web Returns unsupported status
   */
  async checkPermissionNotifications(): Promise<PermissionStatusResults> {
    console.warn(
      'checkPermissionNotifications is not fully supported on web platform. Notifications not applicable for web audio recording.',
    );

    return {
      granted: false,
      status: PermissionStatus.UNSUPPORTED,
    };
  }

  /**
   * Request permissions with detailed options and status information.
   * @param options - Permission request options
   * @returns Promise that resolves with detailed permission status
   * @platform web Returns unsupported status for all permissions
   */
  async requestPermissions(_options?: PermissionRequestOptions): Promise<PermissionStatusResults> {
    void _options; // Parameter for API compatibility
    console.warn(
      'requestPermissions is not fully supported on web platform. For web implementation, consider using navigator.mediaDevices.getUserMedia API directly.',
    );

    return this.checkPermissions();
  }

  // Recording APIs removed on web implementation

  /**
   * Add a listener for recording events
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported
   */
  async addListener<T extends AudioEventName>(
    eventName: T,
    callback: (event: AudioEventMap[T]) => void,
  ): Promise<PluginListenerHandle> {
    console.warn(
      `${eventName} event is not supported on web platform. Callback will not be invoked. For web implementation, consider using MediaRecorder events directly.`,
    );
    const handle = {
      remove: () => Promise.resolve(),
    } as PluginListenerHandle;
    // Store callback in closure to prevent garbage collection
    void callback;
    return handle;
  }

  /**
   * Check if microphone is currently being used by another application.
   * @returns Promise that resolves with microphone status
   * @platform web Not supported
   */
  async isMicrophoneBusy(): Promise<never> {
    console.warn('isMicrophoneBusy is not supported on web platform.');
    throw new Error('isMicrophoneBusy is not supported on web platform');
  }

  /**
   * Get list of available microphones (internal and external).
   * @returns Promise that resolves with available microphones
   * @platform web Not supported
   */
  async getAvailableMicrophones(): Promise<never> {
    console.warn('getAvailableMicrophones is not supported on web platform.');
    throw new Error('getAvailableMicrophones is not supported on web platform');
  }

  /**
   * Switch between microphones while keeping recording active.
   * @param options - Switch microphone options
   * @returns Promise that resolves with switch result
   * @platform web Not supported
   */
  async switchMicrophone(options: { microphoneId: number }): Promise<never> {
    console.warn(
      `switchMicrophone is not supported on web platform. Attempted to switch to microphone ID: ${options.microphoneId}`,
    );
    throw new Error('switchMicrophone is not supported on web platform');
  }

  /**
   * Remove all listeners
   * @returns Promise that resolves when all listeners are removed
   * @platform web Not supported
   */
  async removeAllListeners(): Promise<void> {
    console.warn('removeAllListeners is not supported on web platform.');
  }

  /**
   * Configure waveform data generation settings with default values.
   * @returns Promise that resolves with configuration result
   * @platform web Not supported
   */
  async configureWaveform(options?: { EmissionInterval?: number }): Promise<WaveLevelConfigurationResult> {
    console.warn(
      'configureWaveform is not supported on web platform. Waveform data is not available for web recordings. Consider using MediaRecorder events and manual amplitude analysis.',
    );

    // Build unified response structure
    return {
      success: false,
      configuration: {
        emissionInterval: options?.EmissionInterval ?? 1000,
      },
    };
  }

  /**
   * Destroy waveform configuration and clean up resources.
   * @returns Promise that resolves when waveform configuration is destroyed
   * @platform web Not supported
   */
  async destroyWaveform(): Promise<void> {
    console.warn('destroyWaveform is not supported on web platform. No waveform resources to clean up.');
  }

  // ==================== AUDIO PLAYBACK METHODS ====================

  /**
   * Preload audio tracks from URLs for individual playback
   * @param options - Preload options containing track URLs
   * @returns Promise that resolves with preload results for each track including load status, mimetype, duration, and file size
   * @platform web Not supported
   */
  async preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult> {
    void options; // Parameter for API compatibility
    console.warn(
      'preloadTracks is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );

    // Return empty results for each track URL with loaded=false
    const tracks = options.tracks.map((url) => ({
      url,
      loaded: false,
      duration: 0,
      size: 0,
      mimeType: 'unknown',
    }));

    return { tracks };
  }

  /**
   * Start or resume playback of current track or specific preloaded track by URL
   * @param options - Optional playback options with URL to play specific preloaded track
   * @returns Promise that resolves when playback starts
   * @platform web Not supported
   */
  async playTrack(_options?: { url?: string }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'playAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('playAudio is not supported on web platform');
  }

  /**
   * Pause audio playback for current track or specific preloaded track by URL
   * @param options - Optional pause options with URL to pause specific preloaded track
   * @returns Promise that resolves when playback is paused
   * @platform web Not supported
   */
  async pauseTrack(_options?: { url?: string }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'pauseAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('pauseAudio is not supported on web platform');
  }

  /**
   * Resume audio playback from paused state for current track or specific preloaded track by URL
   * @param options - Optional resume options with URL to resume specific preloaded track
   * @returns Promise that resolves when playback resumes
   * @platform web Not supported
   */
  async resumeTrack(_options?: { url?: string }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'resumeAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('resumeAudio is not supported on web platform');
  }

  /**
   * Stop audio playback and reset to beginning for current track or specific preloaded track by URL
   * @param options - Optional stop options with URL to stop specific preloaded track
   * @returns Promise that resolves when playback stops
   * @platform web Not supported
   */
  async stopTrack(_options?: { url?: string }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'stopAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('stopAudio is not supported on web platform');
  }

  /**
   * Seek to specific position in current track or specific preloaded track by URL
   * @param options - Seek options with time in seconds and optional URL for specific preloaded track
   * @returns Promise that resolves when seek completes
   * @platform web Not supported
   */
  async seekTrack(_options: SeekTrackOptions): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'seekAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('seekAudio is not supported on web platform');
  }

  /**
   * Skip to next track in playlist (simplified - no-op for single track playback)
   * @returns Promise that resolves when skip completes
   * @platform web Not supported
   */
  async skipToNext(): Promise<void> {
    console.warn(
      'skipToNext is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('skipToNext is not supported on web platform');
  }

  /**
   * Skip to previous track in playlist (simplified - no-op for single track playback)
   * @returns Promise that resolves when skip completes
   * @platform web Not supported
   */
  async skipToPrevious(): Promise<void> {
    console.warn(
      'skipToPrevious is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('skipToPrevious is not supported on web platform');
  }

  /**
   * Skip to specific track index in playlist (simplified - no-op for single track playback)
   * @param options - Options with target track index
   * @returns Promise that resolves when skip completes
   * @platform web Not supported
   */
  async skipToIndex(_options: SkipToIndexTrackOptions): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'skipToIndex is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('skipToIndex is not supported on web platform');
  }

  /**
   * Get current playback information
   * @returns Promise that resolves with current playback state
   * @platform web Not supported
   */
  async getPlaybackInfo(): Promise<PlaybackInfo> {
    console.warn(
      'getPlaybackInfo is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('getPlaybackInfo is not supported on web platform');
  }

  /**
   * Destroy and reinitialize the playback manager
   * @returns Promise that resolves when playback manager is destroyed and reinitialized
   * @platform web No-op (browser handles cleanup automatically)
   */
  async destroyPlayback(): Promise<void> {
    // No-op on web - browser handles cleanup automatically
    console.log('destroyPlayback called on web (no-op)');
  }

  /**
   * Navigate to the app's permission settings screen.
   * @returns Promise that resolves when navigation is initiated
   * @platform web Shows alert with instructions to manually open browser settings for permissions
   */
  async openSettings(): Promise<void> {
    console.warn('openSettings is not fully supported on web platform. Showing alert with instructions.');

    const message =
      'To manage app permissions:\n\n' +
      "1. Click the lock icon in your browser's address bar\n" +
      '2. Select "Site settings" or "Permissions"\n' +
      '3. Adjust microphone and other permissions as needed\n\n' +
      'Or access browser settings directly through the browser menu.';

    alert(message);
  }

  async startRecording(_options: { path: string }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn('startRecording is not supported on web platform.');
    throw new Error('startRecording is not supported on web platform');
  }

  async stopRecording(): Promise<AudioFileInfo> {
    console.warn('stopRecording is not supported on web platform.');
    throw new Error('stopRecording is not supported on web platform');
  }

  async pauseRecording(): Promise<void> {
    console.warn('pauseRecording is not supported on web platform.');
    throw new Error('pauseRecording is not supported on web platform');
  }

  async resumeRecording(): Promise<void> {
    console.warn('resumeRecording is not supported on web platform.');
    throw new Error('resumeRecording is not supported on web platform');
  }

  async getRecordingStatus(): Promise<RecordingStatusInfo> {
    console.warn('getRecordingStatus is not supported on web platform.');
    throw new Error('getRecordingStatus is not supported on web platform');
  }

  async trimAudio(_options: TrimTrackOptions): Promise<AudioFileInfo> {
    void _options; // Parameter for API compatibility
    console.warn(
      'trimAudio is not fully supported on web platform. Consider using Web Audio API for client-side audio processing.',
    );
    throw new Error('trimAudio is not supported on web platform');
  }

  async getAudioInfo(): Promise<AudioFileInfo> {
    console.warn('getAudioInfo is not supported on web platform.');
    throw new Error('getAudioInfo is not supported on web platform');
  }
}
