import { Component, OnInit, OnDestroy, signal, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonHeader,
  IonTitle,
  IonToolbar,
  IonCard,
  IonCardContent,
  IonCardHeader,
  IonCardTitle,
  IonButton,
  IonIcon,
  IonItem,
  IonLabel,
  IonList,
  IonBadge,
  IonRange,
  IonText,
  IonToggle,
  IonSpinner,
  IonFab,
  IonFabButton,
  IonBackButton,
  IonButtons,
  AlertController
} from '@ionic/angular/standalone';
import { addIcons } from 'ionicons';
import {
  playOutline,
  pauseOutline,
  stopOutline,
  arrowBackOutline,
  musicalNotesOutline,
  volumeHighOutline,
  speedometerOutline,
  repeatOutline,
  cloudOutline,
  refreshOutline,
  checkmarkCircleOutline,
  playCircleOutline,
  stopCircleOutline
} from 'ionicons/icons';
import { CapacitorAudioEngine, AudioFileInfo } from 'capacitor-audio-engine';

interface AudioRecord {
  id: string;
  name: string;
  uri: string;
  duration: number;
  isPreloaded: boolean;
  isPlaying: boolean;
  isPaused: boolean;
  currentTime: number;
  speed: number;
  volume: number;
  loop: boolean;
}

@Component({
  selector: 'app-multi-audio-resume',
  templateUrl: './multi-audio-resume.component.html',
  styleUrls: ['./multi-audio-resume.component.scss'],
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    IonContent,
    IonHeader,
    IonTitle,
    IonToolbar,
    IonCard,
    IonCardContent,
    IonCardHeader,
    IonCardTitle,
    IonButton,
    IonIcon,
    IonItem,
    IonLabel,
    IonList,
    IonBadge,
    IonRange,
    IonToggle,
    IonSpinner,
    IonFab,
    IonFabButton,
    IonBackButton,
    IonButtons
  ]
})
export class MultiAudioResumeComponent implements OnInit, OnDestroy {
  private readonly alertController = inject(AlertController);

  // Audio records - using sample CDN URLs for demonstration
  audioRecords = signal<AudioRecord[]>([
    {
      id: 'audio1',
      name: 'Sample Audio 1',
      uri: 'https://cdn.pixabay.com/audio/2025/03/19/audio_91b4c0a3b6.mp3',
      duration: 0,
      isPreloaded: false,
      isPlaying: false,
      isPaused: false,
      currentTime: 0,
      speed: 1.0,
      volume: 1.0,
      loop: false
    },
    {
      id: 'audio2',
      name: 'Sample Audio 2',
      uri: 'https://cdn.pixabay.com/audio/2025/02/18/audio_67a824edf7.mp3',
      duration: 0,
      isPreloaded: false,
      isPlaying: false,
      isPaused: false,
      currentTime: 0,
      speed: 1.0,
      volume: 1.0,
      loop: false
    },
    {
      id: 'audio3',
      name: 'Sample Audio 3',
      uri: 'https://cdn.pixabay.com/audio/2024/11/29/audio_45bbd49c34.mp3',
      duration: 0,
      isPreloaded: false,
      isPlaying: false,
      isPaused: false,
      currentTime: 0,
      speed: 1.0,
      volume: 1.0,
      loop: false
    },
    {
      id: 'audio4',
      name: 'Sample Audio 4',
      uri: 'https://cdn.pixabay.com/audio/2024/11/05/audio_da986d1e2a.mp3',
      duration: 0,
      isPreloaded: false,
      isPlaying: false,
      isPaused: false,
      currentTime: 0,
      speed: 1.0,
      volume: 1.0,
      loop: false
    }
  ]);

  isLoading = signal<boolean>(false);
  currentPlayingAudio = signal<string | null>(null);
  globalSpeed = signal<number>(1.0);
  globalVolume = signal<number>(1.0);

  constructor() {
    addIcons({
      playOutline,
      pauseOutline,
      stopOutline,
      arrowBackOutline,
      musicalNotesOutline,
      volumeHighOutline,
      speedometerOutline,
      repeatOutline,
      cloudOutline,
      refreshOutline,
      checkmarkCircleOutline,
      playCircleOutline,
      stopCircleOutline
    });
  }

  ngOnInit() {
    this.setupEventListeners();
    this.preloadAllAudio();
  }

  ngOnDestroy() {
    CapacitorAudioEngine.removeAllListeners();
  }

  private setupEventListeners() {
    // Listen for playback status changes
    CapacitorAudioEngine.addListener('playbackStatusChange', (data) => {
      console.log('Playback status changed:', data);
      this.updateAudioRecordStatus(data);
    });

    // Listen for playback progress
    CapacitorAudioEngine.addListener('playbackProgress', (data) => {
      const currentAudio = this.currentPlayingAudio();
      if (currentAudio) {
        this.updateAudioRecordProgress(currentAudio, data.currentTime);
      }
    });

    // Listen for playback completion
    CapacitorAudioEngine.addListener('playbackCompleted', (data) => {
      console.log('Playback completed:', data);
      const currentAudio = this.currentPlayingAudio();
      if (currentAudio) {
        this.updateAudioRecordToStopped(currentAudio);
      }
    });

    // Listen for playback errors
    CapacitorAudioEngine.addListener('playbackError', (data) => {
      console.error('Playback error:', data);
      this.showAlert('Playback Error', `Error: ${data.message}`);
      const currentAudio = this.currentPlayingAudio();
      if (currentAudio) {
        this.updateAudioRecordToStopped(currentAudio);
      }
    });
  }

  private updateAudioRecordStatus(data: any) {
    const records = this.audioRecords();
    const currentAudio = this.currentPlayingAudio();

    if (currentAudio) {
      const recordIndex = records.findIndex(r => r.id === currentAudio);
      if (recordIndex >= 0) {
        const updatedRecords = [...records];
        updatedRecords[recordIndex] = {
          ...updatedRecords[recordIndex],
          isPlaying: data.status === 'playing',
          isPaused: data.status === 'paused',
          currentTime: data.currentTime || 0,
          duration: data.duration || updatedRecords[recordIndex].duration
        };
        this.audioRecords.set(updatedRecords);
      }
    }
  }

  private updateAudioRecordProgress(audioId: string, currentTime: number) {
    const records = this.audioRecords();
    const recordIndex = records.findIndex(r => r.id === audioId);
    if (recordIndex >= 0) {
      const updatedRecords = [...records];
      updatedRecords[recordIndex] = {
        ...updatedRecords[recordIndex],
        currentTime
      };
      this.audioRecords.set(updatedRecords);
    }
  }

  private updateAudioRecordToStopped(audioId: string) {
    const records = this.audioRecords();
    const recordIndex = records.findIndex(r => r.id === audioId);
    if (recordIndex >= 0) {
      const updatedRecords = [...records];
      updatedRecords[recordIndex] = {
        ...updatedRecords[recordIndex],
        isPlaying: false,
        isPaused: false,
        currentTime: 0
      };
      this.audioRecords.set(updatedRecords);
    }
    this.currentPlayingAudio.set(null);
  }

  async preloadAllAudio() {
    this.isLoading.set(true);
    try {
      const records = this.audioRecords();
      for (const record of records) {
        try {
          await CapacitorAudioEngine.preload({
            uri: record.uri,
            prepare: true
          });

          // Get audio info for duration
          const audioInfo = await CapacitorAudioEngine.getAudioInfo({
            uri: record.uri
          });

          // Update record with preloaded status and duration
          const updatedRecords = [...this.audioRecords()];
          const recordIndex = updatedRecords.findIndex(r => r.id === record.id);
          if (recordIndex >= 0) {
            updatedRecords[recordIndex] = {
              ...updatedRecords[recordIndex],
              isPreloaded: true,
              duration: audioInfo.duration
            };
          }
          this.audioRecords.set(updatedRecords);

          console.log(`Preloaded ${record.name}`);
        } catch (error) {
          console.error(`Failed to preload ${record.name}:`, error);
        }
      }
    } catch (error) {
      console.error('Failed to preload audio files:', error);
    } finally {
      this.isLoading.set(false);
    }
  }

  async playAudioRecord(audioId: string) {
    try {
      const record = this.audioRecords().find(r => r.id === audioId);
      if (!record) return;

      // Stop any currently playing audio
      if (this.currentPlayingAudio()) {
        await CapacitorAudioEngine.stopPlayback();
      }

      // Start playing the selected audio
      await CapacitorAudioEngine.startPlayback({
        uri: record.uri,
        speed: record.speed,
        volume: record.volume,
        loop: record.loop,
        startTime: record.currentTime
      });

      this.currentPlayingAudio.set(audioId);
      console.log(`Started playing ${record.name}`);
    } catch (error) {
      console.error(`Failed to play ${audioId}:`, error);
      this.showAlert('Playback Error', `Failed to play audio: ${error}`);
    }
  }

  async pauseAudioRecord(audioId: string) {
    try {
      if (this.currentPlayingAudio() === audioId) {
        await CapacitorAudioEngine.pausePlayback();
        console.log(`Paused ${audioId}`);
      }
    } catch (error) {
      console.error(`Failed to pause ${audioId}:`, error);
      this.showAlert('Playback Error', `Failed to pause audio: ${error}`);
    }
  }

  async resumeAudioRecord(audioId: string) {
    try {
      const record = this.audioRecords().find(r => r.id === audioId);
      if (!record) return;

      // Use the new resumePlayback with options
      await CapacitorAudioEngine.resumePlayback({
        uri: record.uri,
        speed: record.speed,
        volume: record.volume,
        loop: record.loop
      });

      this.currentPlayingAudio.set(audioId);
      console.log(`Resumed playing ${record.name} with options`);
    } catch (error) {
      console.error(`Failed to resume ${audioId}:`, error);
      this.showAlert('Playback Error', `Failed to resume audio: ${error}`);
    }
  }

  async stopAudioRecord(audioId: string) {
    try {
      if (this.currentPlayingAudio() === audioId) {
        await CapacitorAudioEngine.stopPlayback();
        this.currentPlayingAudio.set(null);
        console.log(`Stopped ${audioId}`);
      }
    } catch (error) {
      console.error(`Failed to stop ${audioId}:`, error);
      this.showAlert('Playback Error', `Failed to stop audio: ${error}`);
    }
  }

  updateAudioSpeed(audioId: string, speed: number) {
    const records = this.audioRecords();
    const recordIndex = records.findIndex(r => r.id === audioId);
    if (recordIndex >= 0) {
      const updatedRecords = [...records];
      updatedRecords[recordIndex] = {
        ...updatedRecords[recordIndex],
        speed
      };
      this.audioRecords.set(updatedRecords);
    }
  }

  updateAudioVolume(audioId: string, volume: number) {
    const records = this.audioRecords();
    const recordIndex = records.findIndex(r => r.id === audioId);
    if (recordIndex >= 0) {
      const updatedRecords = [...records];
      updatedRecords[recordIndex] = {
        ...updatedRecords[recordIndex],
        volume
      };
      this.audioRecords.set(updatedRecords);
    }
  }

  onSpeedChange(audioId: string, event: any) {
    const value = event.detail.value;
    const speed = typeof value === 'number' ? value : (value as any).lower || (value as any).upper || 1.0;
    this.updateAudioSpeed(audioId, speed);
  }

  onVolumeChange(audioId: string, event: any) {
    const value = event.detail.value;
    const volume = typeof value === 'number' ? value : (value as any).lower || (value as any).upper || 1.0;
    this.updateAudioVolume(audioId, volume);
  }

  onGlobalSpeedChange(event: any) {
    const value = event.detail.value;
    const speed = typeof value === 'number' ? value : (value as any).lower || (value as any).upper || 1.0;
    this.globalSpeed.set(speed);
  }

  onGlobalVolumeChange(event: any) {
    const value = event.detail.value;
    const volume = typeof value === 'number' ? value : (value as any).lower || (value as any).upper || 1.0;
    this.globalVolume.set(volume);
  }

  toggleAudioLoop(audioId: string) {
    const records = this.audioRecords();
    const recordIndex = records.findIndex(r => r.id === audioId);
    if (recordIndex >= 0) {
      const updatedRecords = [...records];
      updatedRecords[recordIndex] = {
        ...updatedRecords[recordIndex],
        loop: !updatedRecords[recordIndex].loop
      };
      this.audioRecords.set(updatedRecords);
    }
  }

  async applyGlobalSettings() {
    const records = this.audioRecords();
    const updatedRecords = records.map(record => ({
      ...record,
      speed: this.globalSpeed(),
      volume: this.globalVolume()
    }));
    this.audioRecords.set(updatedRecords);

    this.showAlert('Settings Applied', 'Global speed and volume settings have been applied to all audio records.');
  }

  formatTime(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  }

  getProgressPercentage(record: AudioRecord): number {
    if (record.duration === 0) return 0;
    return (record.currentTime / record.duration) * 100;
  }

  private async showAlert(header: string, message: string) {
    const alert = await this.alertController.create({
      header,
      message,
      buttons: ['OK']
    });
    await alert.present();
  }
}
