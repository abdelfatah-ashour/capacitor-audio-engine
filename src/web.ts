import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type {
  CapacitorAudioEnginePlugin,
  RecordingStatus,
  AudioRecordingEventName,
  AudioFileInfo,
  AudioRecordingEventMap,
  RecordingOptions,
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
  async checkPermission(): Promise<{ granted: boolean }> {
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
  async requestPermission(): Promise<{ granted: boolean }> {
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
    // The _options parameter is intentionally unused but added for API compatibility
    void _options;
    console.warn(
      'startRecording is not supported on web platform. For web implementation, consider using MediaRecorder API directly.',
    );

    // Reset recording state and start monitoring
    this.recordingStartTime = Date.now();
    this.isPaused = false;
    await this.startMonitoring();
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

    // Stop monitoring duration
    await this.stopMonitoring();
    this.recordingStartTime = 0;
    this.isPaused = false;

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
    this.isPaused = true;
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
    this.isPaused = false;
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
   * Add a listener for recording events
   * @returns A promise that resolves with a handle to the listener
   * @platform web Not supported
   */
  async addListener<T extends AudioRecordingEventName>(
    eventName: T,
    callback: (event: AudioRecordingEventMap[T]) => void,
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
  async isMicrophoneBusy(): Promise<{ busy: boolean }> {
    console.warn('isMicrophoneBusy is not supported on web platform.');
    throw new Error('isMicrophoneBusy is not supported on web platform');
  }

  /**
   * Get list of available microphones (internal and external).
   * @returns Promise that resolves with available microphones
   * @platform web Not supported
   */
  async getAvailableMicrophones(): Promise<{ microphones: any[] }> {
    console.warn('getAvailableMicrophones is not supported on web platform.');
    throw new Error('getAvailableMicrophones is not supported on web platform');
  }

  /**
   * Switch between microphones while keeping recording active.
   * @param options - Switch microphone options
   * @returns Promise that resolves with switch result
   * @platform web Not supported
   */
  async switchMicrophone(options: { microphoneId: number }): Promise<{ success: boolean; microphoneId: number }> {
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

  private durationMonitoringInterval?: number;
  private recordingStartTime = 0;
  private isPaused = false;

  /**
   * Start monitoring duration. Used internally by start/pause/resume recording functions.
   * @returns Promise that resolves when monitoring starts
   * @private
   */
  private async startMonitoring(): Promise<void> {
    // Clear any existing interval
    this.stopMonitoring();

    if (!this.recordingStartTime) {
      this.recordingStartTime = Date.now();
    }

    // Set up interval to emit duration changes (1 second interval)
    this.durationMonitoringInterval = window.setInterval(() => {
      if (!this.isPaused) {
        const duration = Math.floor((Date.now() - this.recordingStartTime) / 1000);
        console.log('Web: Emitting durationChange event with duration:', duration);
        this.notifyListeners('durationChange', {
          eventName: 'durationChange',
          payload: { duration },
        });
      }
    }, 1000) as unknown as number;
  }

  /**
   * Stop monitoring duration. Used internally by stop recording function.
   * @returns Promise that resolves when monitoring stops
   * @private
   */
  private async stopMonitoring(): Promise<void> {
    if (this.durationMonitoringInterval) {
      clearInterval(this.durationMonitoringInterval);
      this.durationMonitoringInterval = undefined;
    }
  }
}
