import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorAudioEnginePlugin,
  RecordingStatus,
  AudioEventName,
  AudioFileInfo,
  AudioEventMap,
  RecordingOptions,
  MicrophoneStatusResult,
  AvailableMicrophonesResult,
  SwitchMicrophoneResult,
  SwitchMicrophoneOptions,
  WaveformConfigurationResult,
  PreloadTracksOptions,
  PreloadTracksResult,
  PlaybackInfo,
  SeekOptions,
  SkipToIndexOptions,
  SetGainFactorOptions,
  SetGainFactorResult,
} from './definitions';

declare global {
  interface BlobEventInit extends EventInit {
    data: Blob;
  }

  interface BlobEvent extends Event {
    readonly data: Blob;
  }
}

export class CapacitorAudioEngineWeb extends WebPlugin implements CapacitorAudioEnginePlugin {
  async echo(options: { value: string }): Promise<{ value: string }> {
    console.log('ECHO', options);
    return options;
  }

  /**
   * Check if the app has microphone permission.
   * @returns Promise that resolves with an object containing the permission status
   * @platform web Not supported - returns false
   */
  async checkPermission(): Promise<{ granted: boolean; audioPermission?: boolean; notificationPermission?: boolean }> {
    console.warn(
      'checkPermission is not supported on web platform. For web implementation, consider using navigator.permissions.query API directly.',
    );
    return { granted: false };
  }

  /**
   * Request microphone permission from the user.
   * @returns Promise that resolves with an object containing the permission status
   * @platform web Not supported - returns false
   */
  async requestPermission(): Promise<{
    granted: boolean;
    audioPermission?: boolean;
    notificationPermission?: boolean;
  }> {
    console.warn(
      'requestPermission is not supported on web platform. For web implementation, consider using navigator.mediaDevices.getUserMedia API directly.',
    );
    return { granted: false };
  }

  /**
   * Start recording audio from the device's microphone.
   * @param options - Recording options

   * @returns Promise that resolves when recording starts successfully
   * @platform web Not supported
   */
  async startRecording(_options?: RecordingOptions): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'startRecording is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    throw new Error('startRecording is not supported on web platform');
  }

  /**
   * Stop the current recording and get the recorded file information.
   * @returns Promise that resolves with the recorded audio file details
   * @platform web Not supported
   */
  async stopRecording(): Promise<AudioFileInfo> {
    console.warn(
      'stopRecording is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    throw new Error('stopRecording is not supported on web platform');
  }

  /**
   * Pause the current recording.
   * @returns Promise that resolves when recording is paused successfully
   * @platform web Not supported
   */
  async pauseRecording(): Promise<void> {
    console.warn(
      'pauseRecording is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    throw new Error('pauseRecording is not supported on web platform');
  }

  /**
   * Resume the current recording if it was previously paused.
   * @returns Promise that resolves when recording is resumed successfully
   * @platform web Not supported
   */
  async resumeRecording(): Promise<void> {
    console.warn(
      'resumeRecording is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    throw new Error('resumeRecording is not supported on web platform');
  }

  /**
   * Get the current recording duration.
   * @returns Promise that resolves with the current duration in seconds
   * @platform web Not supported - returns 0
   */
  async getDuration(): Promise<{ duration: number }> {
    console.warn(
      'getDuration is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    return { duration: 0 };
  }

  /**
   * Get the current recording status.
   * @returns Promise that resolves with the current recording status
   * @platform web Not supported - returns idle
   */
  async getStatus(): Promise<{
    status: RecordingStatus;
    isRecording: boolean;

    duration: number;
  }> {
    console.warn(
      'getStatus is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    return { status: 'idle', isRecording: false, duration: 0 };
  }

  /**
   * Trim an audio file to the specified start and end times.
   * @returns Promise that resolves with the trimmed audio file details
   * @platform web Not supported
   */
  async trimAudio({ uri, start, end }: { uri: string; start: number; end: number }): Promise<AudioFileInfo> {
    console.warn(
      `trimAudio is not supported on web platform. Attempted to trim file: ${uri} from ${start}s to ${end}s. For web implementation, consider using Web Audio API directly.`,
    );
    throw new Error('trimAudio is not supported on web platform');
  }

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
  async isMicrophoneBusy(): Promise<MicrophoneStatusResult> {
    console.warn('isMicrophoneBusy is not supported on web platform.');
    throw new Error('isMicrophoneBusy is not supported on web platform');
  }

  /**
   * Get list of available microphones (internal and external).
   * @returns Promise that resolves with available microphones
   * @platform web Not supported
   */
  async getAvailableMicrophones(): Promise<AvailableMicrophonesResult> {
    console.warn('getAvailableMicrophones is not supported on web platform.');
    throw new Error('getAvailableMicrophones is not supported on web platform');
  }

  /**
   * Switch between microphones while keeping recording active.
   * @param options - Switch microphone options
   * @returns Promise that resolves with switch result
   * @platform web Not supported
   */
  async switchMicrophone(options: SwitchMicrophoneOptions): Promise<SwitchMicrophoneResult> {
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
   * Configure waveform data generation settings (unified configuration).
   * @param options - Unified configuration options for waveform data
   * @returns Promise that resolves with configuration result
   * @platform web Not supported
   */
  async configureWaveform(): Promise<WaveformConfigurationResult> {
    console.warn(
      'configureWaveform is not supported on web platform. Waveform data is not available for web recordings. Consider using MediaRecorder events and manual amplitude analysis.',
    );

    // Build default configuration (web mock)
    const numberOfBars = 128;
    const debounceTimeMs = 50; // align with native default (50ms)

    // Build unified response structure
    return {
      success: false,
      configuration: {
        numberOfBars,
        debounceTimeMs,
        speechDetection: {
          enabled: false,
          threshold: 0.02,
          calibrationDuration: 1000,
        },
        vad: {
          enabled: false,
          windowSize: 5,
          estimatedLatencyMs: 5 * 50,
          enableVoiceFilter: true,
        },
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
   * Preload audio tracks from URLs and initialize playlist
   * @param options - Preload options containing track URLs and preload settings
   * @returns Promise that resolves with preload results for each track including load status, mimetype, duration, and file size
   * @platform web Not supported
   */
  async preloadTracks(options: PreloadTracksOptions): Promise<PreloadTracksResult> {
    console.warn(
      'preloadTracks is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );

    // Return empty results for each track URL with loaded=false
    const tracks = options.tracks.map((url) => ({
      url,
      loaded: false,
    }));

    return { tracks };
  }

  /**
   * Start or resume playback of current track
   * @returns Promise that resolves when playback starts
   * @platform web Not supported
   */
  async playAudio(): Promise<void> {
    console.warn(
      'playAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('playAudio is not supported on web platform');
  }

  /**
   * Pause audio playback
   * @returns Promise that resolves when playback is paused
   * @platform web Not supported
   */
  async pauseAudio(): Promise<void> {
    console.warn(
      'pauseAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('pauseAudio is not supported on web platform');
  }

  /**
   * Resume audio playback from paused state
   * @returns Promise that resolves when playback resumes
   * @platform web Not supported
   */
  async resumeAudio(): Promise<void> {
    console.warn(
      'resumeAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('resumeAudio is not supported on web platform');
  }

  /**
   * Stop audio playback and reset to beginning
   * @returns Promise that resolves when playback stops
   * @platform web Not supported
   */
  async stopAudio(): Promise<void> {
    console.warn(
      'stopAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('stopAudio is not supported on web platform');
  }

  /**
   * Seek to specific position in current track
   * @param options - Seek options with time in seconds
   * @returns Promise that resolves when seek completes
   * @platform web Not supported
   */
  async seekAudio(_options: SeekOptions): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'seekAudio is not supported on web platform. For web implementation, consider using HTML5 Audio API directly.',
    );
    throw new Error('seekAudio is not supported on web platform');
  }

  /**
   * Skip to next track in playlist
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
   * Skip to previous track in playlist
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
   * Skip to specific track index in playlist
   * @param options - Options with target track index
   * @returns Promise that resolves when skip completes
   * @platform web Not supported
   */
  async skipToIndex(_options: SkipToIndexOptions): Promise<void> {
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
   * Set gain factor for waveform visualization levels
   * @param options - Gain factor options
   * @returns Promise that resolves with gain factor result
   * @platform web Not supported
   */
  async setGainFactor(options: SetGainFactorOptions): Promise<SetGainFactorResult> {
    console.warn(
      `setGainFactor is not supported on web platform. Attempted to set gain factor: ${options.gainFactor}`,
    );

    // Return mock success with provided gain factor for API compatibility
    return {
      success: false,
      gainFactor: options.gainFactor,
    };
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
}
