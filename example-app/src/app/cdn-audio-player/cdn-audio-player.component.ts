import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonicModule } from '@ionic/angular';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import { PluginListenerHandle } from '@capacitor/core';

const urlAudio = "https://cdn.pixabay.com/audio/2025/03/19/audio_91b4c0a3b6.mp3";

const urlAudio2 = "https://cdn.pixabay.com/audio/2025/02/18/audio_67a824edf7.mp3";

const urlAudio3 = "https://cdn.pixabay.com/audio/2024/11/29/audio_45bbd49c34.mp3";

const urlAudio4 = "https://cdn.pixabay.com/audio/2024/11/05/audio_da986d1e2a.mp3";

@Component({
  selector: 'app-cdn-audio-player',
  standalone: true,
  imports: [CommonModule, FormsModule, IonicModule],
  templateUrl: './cdn-audio-player.component.html',
  styleUrls: ['./cdn-audio-player.component.scss']
})
export class CdnAudioPlayerComponent implements OnInit, OnDestroy {

  // Sample CDN audio URLs - replace with your own
  audioUrls = [
    {
      name: 'Sample Audio 1',
      url:urlAudio,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 2',
      url: urlAudio2,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 3',
      url: urlAudio3,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 4',
      url: urlAudio4,
      preloaded: false,
      loading: false
    }
  ];

  currentlyPlaying: string | null = null;
  playbackStatus: any = {
    status: 'idle',
    currentTime: 0,
    duration: 0,
    position: 0
  };

  // Playback controls
  playbackSpeed = 1.0;
  volume = 1.0;
  isLooping = false;

  private listeners: PluginListenerHandle[] = [];

  constructor() { }

  ngOnInit() {
    this.setupPlaybackListeners();
  }

  // Template helper methods
  get allPreloaded(): boolean {
    return this.audioUrls.every(audio => audio.preloaded);
  }

  get anyPlaying(): boolean {
    return this.currentlyPlaying !== null;
  }

  get preloadAllDisabled(): boolean {
    return this.audioUrls.some(audio => audio.loading) || this.allPreloaded;
  }

  get stopAllDisabled(): boolean {
    return !this.anyPlaying;
  }

  getAudioStatus(audioItem: any): string {
    if (audioItem.loading) return 'Loading...';
    if (audioItem.preloaded) return 'Ready';
    return 'Not loaded';
  }

  isCurrentlyPlaying(audioItem: any): boolean {
    return this.currentlyPlaying === audioItem.url;
  }

  canStop(audioItem: any): boolean {
    return this.isPlaying(audioItem) || this.isPaused(audioItem);
  }

  ngOnDestroy() {
    this.removeAllListeners();
    this.stopAllPlayback();
  }

  private async setupPlaybackListeners() {
    try {
      // Listen for playback status changes
      const statusListener = await CapacitorAudioEngine.addListener('playbackStatusChange', (event) => {
        console.log('Playback status changed:', event);
        this.playbackStatus = { ...this.playbackStatus, ...event };
      });

      // Listen for playback progress
      const progressListener = await CapacitorAudioEngine.addListener('playbackProgress', (event) => {
        console.log('Playback progress:', event);
        this.playbackStatus = { ...this.playbackStatus, ...event };
      });

      // Listen for playback completion
      const completedListener = await CapacitorAudioEngine.addListener('playbackCompleted', (event) => {
        console.log('Playback completed:', event);
        this.currentlyPlaying = null;
        this.playbackStatus.status = 'completed';
      });

      // Listen for playback errors
      const errorListener = await CapacitorAudioEngine.addListener('playbackError', (event) => {
        console.error('Playback error:', event);
        this.currentlyPlaying = null;
        this.playbackStatus.status = 'error';
        this.showToast('Playback error: ' + event.message, 'danger');
      });

      this.listeners = [statusListener, progressListener, completedListener, errorListener];
    } catch (error) {
      console.error('Failed to setup playback listeners:', error);
    }
  }

  async preloadAudio(audioItem: any) {
    if (audioItem.preloaded || audioItem.loading) return;

    audioItem.loading = true;
    try {
      const results = await CapacitorAudioEngine.preload({
        uri: audioItem.url,
        prepare: true
      });
      console.log("🚀 ~ CdnAudioPlayerComponent ~ preloadAudio ~ results:", results)

      audioItem.preloaded = true;
      audioItem.loading = false;
      this.showToast(`${audioItem.name} preloaded successfully`, 'success');
    } catch (error) {
      console.error('Failed to preload audio:', error);
      audioItem.loading = false;
      this.showToast(`Failed to preload ${audioItem.name}`, 'danger');
    }
  }

  async preloadAllAudio() {
    for (const audioItem of this.audioUrls) {
      if (!audioItem.preloaded && !audioItem.loading) {
        await this.preloadAudio(audioItem);
        // Add a small delay between preloads to prevent overwhelming the system
        await new Promise(resolve => setTimeout(resolve, 500));
      }
    }
  }

  async playAudio(audioItem: any) {
    try {
      // Stop current playback if any
      if (this.currentlyPlaying) {
        await this.stopPlayback();
      }

      // Start new playback
      await CapacitorAudioEngine.startPlayback({
        uri: audioItem.url,
        speed: this.playbackSpeed,
        volume: this.volume,
        loop: this.isLooping,
        startTime: 0
      });

      this.currentlyPlaying = audioItem.url;
      this.showToast(`Playing ${audioItem.name}`, 'primary');
    } catch (error) {
      console.error('Failed to play audio:', error);
      this.showToast(`Failed to play ${audioItem.name}`, 'danger');
    }
  }

  async pausePlayback() {
    try {
      await CapacitorAudioEngine.pausePlayback();
      this.showToast('Playback paused', 'medium');
    } catch (error) {
      console.error('Failed to pause playback:', error);
      this.showToast('Failed to pause playback', 'danger');
    }
  }

  async resumePlayback() {
    try {
      await CapacitorAudioEngine.resumePlayback();
      this.showToast('Playback resumed', 'primary');
    } catch (error) {
      console.error('Failed to resume playback:', error);
      this.showToast('Failed to resume playback', 'danger');
    }
  }

  async stopPlayback() {
    try {
      await CapacitorAudioEngine.stopPlayback();
      this.currentlyPlaying = null;
      this.playbackStatus = {
        status: 'stopped',
        currentTime: 0,
        duration: 0,
        position: 0
      };
      this.showToast('Playback stopped', 'medium');
    } catch (error) {
      console.error('Failed to stop playback:', error);
      this.showToast('Failed to stop playback', 'danger');
    }
  }

  async stopAllPlayback() {
    try {
      await CapacitorAudioEngine.destroyAllPlaybacks();
      this.currentlyPlaying = null;
      this.playbackStatus = {
        status: 'idle',
        currentTime: 0,
        duration: 0,
        position: 0
      };
    } catch (error) {
      console.error('Failed to stop all playback:', error);
    }
  }

  async seekTo(time: number) {
    try {
      await CapacitorAudioEngine.seekTo({ time });
    } catch (error) {
      console.error('Failed to seek:', error);
      this.showToast('Failed to seek', 'danger');
    }
  }

  onSeek(event: any) {
    const seekTime = (event.detail.value / 100) * this.playbackStatus.duration;
    this.seekTo(seekTime);
  }

  onSpeedChange() {
    // Speed change requires restarting playback with new speed
    if (this.currentlyPlaying) {
      const currentAudio = this.audioUrls.find(audio => audio.url === this.currentlyPlaying);
      if (currentAudio) {
        this.playAudio(currentAudio);
      }
    }
  }

  onVolumeChange() {
    // Volume change requires restarting playback with new volume
    if (this.currentlyPlaying) {
      const currentAudio = this.audioUrls.find(audio => audio.url === this.currentlyPlaying);
      if (currentAudio) {
        this.playAudio(currentAudio);
      }
    }
  }

  async getPlaybackStatus() {
    try {
      const status = await CapacitorAudioEngine.getPlaybackStatus();
      this.playbackStatus = status;
      console.log('Current playback status:', status);
    } catch (error) {
      console.error('Failed to get playback status:', error);
    }
  }

  formatTime(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  isPlaying(audioItem: any): boolean {
    return this.currentlyPlaying === audioItem.url && this.playbackStatus.status === 'playing';
  }

  isPaused(audioItem: any): boolean {
    return this.currentlyPlaying === audioItem.url && this.playbackStatus.status === 'paused';
  }

  private async removeAllListeners() {
    for (const listener of this.listeners) {
      await listener.remove();
    }
    this.listeners = [];
  }

  private async showToast(message: string, color: string) {
    // This would typically use Ionic's ToastController
    // For now, just console.log
    console.log(`[${color.toUpperCase()}] ${message}`);
  }
}
