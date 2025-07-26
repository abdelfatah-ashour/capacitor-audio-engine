import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorAudioEnginePlugin,
  RecordingStatus,
  PlaybackStatus,
  AudioEventName,
  AudioFileInfo,
  AudioEventMap,
  RecordingOptions,
  MicrophoneStatusResult,
  AvailableMicrophonesResult,
  SwitchMicrophoneResult,
  SwitchMicrophoneOptions,
  AudioTrack,
  PlaylistOptions,
  PlaybackInfo,
  SeekOptions,
  SkipToIndexOptions,
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

  // ==================== AUDIO PLAYBACK METHODS ====================

  private playlist: AudioTrack[] = [];
  private currentIndex = 0;
  private audio: HTMLAudioElement | null = null;
  private status: PlaybackStatus = 'idle';

  /**
   * Initialize playlist with audio tracks and preload first track
   * @param options - Playlist options containing tracks and preload settings
   * @returns Promise that resolves when playlist is initialized
   * @platform web Uses HTML5 Audio API
   */
  async initPlaylist(options: PlaylistOptions): Promise<void> {
    this.playlist = [...options.tracks];
    this.currentIndex = 0;
    this.status = 'loading';

    if (this.playlist.length > 0) {
      this.audio = new Audio(this.playlist[0].url);
      this.setupAudioEventListeners();
      this.status = 'idle';
    }
  }

  /**
   * Start or resume playback of current track
   * @returns Promise that resolves when playback starts
   */
  async playAudio(): Promise<void> {
    if (!this.audio) {
      throw new Error('No audio initialized. Call initPlaylist first.');
    }

    try {
      await this.audio.play();
      this.status = 'playing';
    } catch (error) {
      throw new Error(`Failed to play audio: ${error}`);
    }
  }

  /**
   * Pause audio playback
   * @returns Promise that resolves when playback is paused
   */
  async pauseAudio(): Promise<void> {
    if (this.audio) {
      this.audio.pause();
      this.status = 'paused';
    }
  }

  /**
   * Resume audio playback from paused state
   * @returns Promise that resolves when playback resumes
   */
  async resumeAudio(): Promise<void> {
    return this.playAudio();
  }

  /**
   * Stop audio playback and reset to beginning
   * @returns Promise that resolves when playback stops
   */
  async stopAudio(): Promise<void> {
    if (this.audio) {
      this.audio.pause();
      this.audio.currentTime = 0;
      this.status = 'stopped';
    }
  }

  /**
   * Seek to specific position in current track
   * @param options - Seek options with time in seconds
   * @returns Promise that resolves when seek completes
   */
  async seekAudio(options: SeekOptions): Promise<void> {
    if (this.audio) {
      this.audio.currentTime = options.seconds;
    }
  }

  /**
   * Skip to next track in playlist
   * @returns Promise that resolves when skip completes
   */
  async skipToNext(): Promise<void> {
    if (this.currentIndex < this.playlist.length - 1) {
      this.currentIndex++;
      await this.switchToCurrentTrack();
    }
  }

  /**
   * Skip to previous track in playlist
   * @returns Promise that resolves when skip completes
   */
  async skipToPrevious(): Promise<void> {
    if (this.currentIndex > 0) {
      this.currentIndex--;
      await this.switchToCurrentTrack();
    }
  }

  /**
   * Skip to specific track index in playlist
   * @param options - Options with target track index
   * @returns Promise that resolves when skip completes
   */
  async skipToIndex(options: SkipToIndexOptions): Promise<void> {
    if (options.index >= 0 && options.index < this.playlist.length) {
      this.currentIndex = options.index;
      await this.switchToCurrentTrack();
    }
  }

  /**
   * Get current playback information
   * @returns Promise that resolves with current playback state
   */
  async getPlaybackInfo(): Promise<PlaybackInfo> {
    const currentTrack = this.getCurrentTrack();

    return {
      currentTrack,
      currentIndex: this.currentIndex,
      currentPosition: this.audio?.currentTime || 0,
      duration: this.audio?.duration || 0,
      isPlaying: this.status === 'playing',
      status: this.status,
    };
  }

  private getCurrentTrack(): AudioTrack | null {
    if (this.currentIndex >= 0 && this.currentIndex < this.playlist.length) {
      return this.playlist[this.currentIndex];
    }
    return null;
  }

  private async switchToCurrentTrack(): Promise<void> {
    const currentTrack = this.getCurrentTrack();
    if (!currentTrack) return;

    const wasPlaying = this.status === 'playing';

    if (this.audio) {
      this.audio.src = currentTrack.url;
      this.audio.load();

      if (wasPlaying) {
        await this.playAudio();
      }
    }
  }

  private setupAudioEventListeners(): void {
    if (!this.audio) return;

    this.audio.addEventListener('ended', () => {
      // Auto-advance to next track
      if (this.currentIndex < this.playlist.length - 1) {
        this.skipToNext();
      } else {
        this.status = 'stopped';
      }
    });

    this.audio.addEventListener('error', () => {
      console.error('Audio playback error');
      this.status = 'idle';
    });
  }
}
