import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorAudioEnginePlugin,
  RecordingStatus,
  AudioEventName,
  AudioFileInfo,
  AudioPlayerInfo,
  AudioEventMap,
  RecordingOptions,
  MicrophoneStatusResult,
  AvailableMicrophonesResult,
  SwitchMicrophoneResult,
  SwitchMicrophoneOptions,
  GetAudioInfoOptions,
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
   * @param options.maxDuration - Maximum duration in seconds to keep at the end of recording
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
    currentSegment: number;
    duration: number;
  }> {
    console.warn(
      'getStatus is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );
    return { status: 'idle', isRecording: false, currentSegment: 0, duration: 0 };
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
   * Add a listener for recording or playback events
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
   * Start audio playback.
   * @returns Promise that resolves when playback starts
   * @platform web Not supported
   */
  async startPlayback(_options: any): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'startPlayback is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('startPlayback is not supported on web platform');
  }

  /**
   * Pause the current playback.
   * @returns Promise that resolves when playback is paused
   * @platform web Not supported
   */
  async pausePlayback(): Promise<void> {
    console.warn(
      'pausePlayback is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('pausePlayback is not supported on web platform');
  }

  /**
   * Resume the current playback.
   * @returns Promise that resolves when playback is resumed
   * @platform web Not supported
   */
  async resumePlayback(): Promise<void> {
    console.warn(
      'resumePlayback is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('resumePlayback is not supported on web platform');
  }

  /**
   * Stop the current playback.
   * @returns Promise that resolves when playback is stopped
   * @platform web Not supported
   */
  async stopPlayback(): Promise<void> {
    console.warn(
      'stopPlayback is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('stopPlayback is not supported on web platform');
  }

  /**
   * Seek to a specific time in the audio.
   * @returns Promise that resolves when seek is complete
   * @platform web Not supported
   */
  async seekTo(_options: { time: number }): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'seekTo is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('seekTo is not supported on web platform');
  }

  /**
   * Get the current playback status.
   * @returns Promise that resolves with the current playback status
   * @platform web Not supported
   */
  async getPlaybackStatus(): Promise<AudioPlayerInfo> {
    console.warn(
      'getPlaybackStatus is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    return {
      status: 'idle',
      currentTime: 0,
      duration: 0,
      speed: 1.0,
      volume: 1.0,
      isLooping: false,
      uri: '',
    };
  }

  /**
   * Add a listener for playback events
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported
   */
  async addPlaybackListener(_eventName: string, _callback: (event: any) => void): Promise<PluginListenerHandle> {
    void _eventName; // Parameter for API compatibility
    void _callback; // Parameter for API compatibility
    console.warn(
      'addPlaybackListener is not supported on web platform. For web implementation, consider using AudioContext events directly.',
    );
    return {
      remove: async () => Promise.resolve(),
    };
  }

  /**
   * Remove all playback listeners
   * @returns Promise that resolves when all listeners are removed
   * @platform web Not supported
   */
  async removeAllPlaybackListeners(): Promise<void> {
    console.warn('removeAllPlaybackListeners is not supported on web platform.');
  }

  /**
   * Preload an audio file for faster playback start.
   * @returns Promise that resolves when preloading is complete
   * @platform web Not supported
   */
  async preload(_options: any): Promise<void> {
    void _options; // Parameter for API compatibility
    console.warn(
      'preload is not supported on web platform. For web implementation, consider using AudioContext API directly.',
    );
    throw new Error('preload is not supported on web platform');
  }

  /**
   * Get information about an audio file.
   * @param options - Options for getting audio info
   * @returns Promise that resolves with audio file information
   * @platform web Not supported
   */
  async getAudioInfo(_options: GetAudioInfoOptions): Promise<AudioFileInfo> {
    void _options; // Parameter for API compatibility
    console.warn(
      'getAudioInfo is not supported on web platform. For web implementation, consider using Web Audio API directly.',
    );
    throw new Error('getAudioInfo is not supported on web platform');
  }

  /**
   * Destroy all active playback sessions and clear all preloaded audio.
   * @returns Promise that resolves when all playback resources are destroyed
   * @platform web Not supported
   */
  async destroyAllPlaybacks(): Promise<void> {
    console.warn(
      'destroyAllPlaybacks is not supported on web platform. For web implementation, consider using Web Audio API directly.',
    );
    throw new Error('destroyAllPlaybacks is not supported on web platform');
  }
}
