import type { PluginListenerHandle } from '@capacitor/core';
import { WebPlugin } from '@capacitor/core';

import type { CapacitorAudioEnginePlugin, RecordingStatus, AudioRecordingEventName } from './definitions';

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

  async checkPermission(): Promise<{ granted: boolean }> {
    console.error('checkPermission is not supported on web');
    return { granted: false };
  }

  async requestPermission(): Promise<{ granted: boolean }> {
    console.error('requestPermission is not supported on web');
    return { granted: false };
  }

  async startRecording(): Promise<void> {
    console.error('startRecording is not supported on web');
  }

  async stopRecording(): Promise<{
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
  }> {
    return new Promise((_, reject) => {
      console.error('stopRecording is not supported on web');
      reject(new Error('stopRecording is not supported on web'));
    });
  }

  async pauseRecording(): Promise<void> {
    console.error('pauseRecording is not supported on web');
  }

  async resumeRecording(): Promise<void> {
    console.error('resumeRecording is not supported on web');
  }

  async getDuration(): Promise<{ duration: number }> {
    console.error('getDuration is not supported on web');
    return { duration: 0 };
  }

  async getStatus(): Promise<{ status: RecordingStatus; isRecording: boolean }> {
    console.error('getStatus is not supported on web');
    return { status: 'idle', isRecording: false };
  }

  async trimAudio(options: { path: string; startTime: number; endTime: number }): Promise<{
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
  }> {
    console.error('trimAudio is not supported on web', options);
    throw new Error('trimAudio is not supported on web');
  }

  async addListener(
    eventName: AudioRecordingEventName,
    callback: (data: { message: string }) => void,
  ): Promise<PluginListenerHandle> {
    console.log('addListener is not supported on web', { eventName, callback });
    // For web, we can call super.addListener to handle event registration if needed for other event types in the future.
    // However, for 'recordingInterruption', it's not applicable to the web environment in the same way as native.
    // We'll return a dummy handle as before, but acknowledge the specific event type.
    if (eventName === 'recordingInterruption') {
      console.warn('recordingInterruption event is not applicable on the web platform.');
    }
    return Promise.resolve({ remove: () => Promise.resolve() } as PluginListenerHandle);
  }

  async startMonitoring(): Promise<void> {
    console.error('startMonitoring is not supported on web');
  }

  async stopMonitoring(): Promise<void> {
    console.error('stopMonitoring is not supported on web');
  }
}
