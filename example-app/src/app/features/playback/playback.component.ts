import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import {
  IonContent,
  IonHeader,
  IonTitle,
  IonToolbar,
  IonCard,
  IonCardHeader,
  IonCardTitle,
  IonCardSubtitle,
  IonCardContent,
  IonButton,
  IonIcon,
  IonList,
  IonItem,
  IonLabel,
  IonGrid,
  IonRow,
  IonCol,
  IonProgressBar,
  IonButtons,
  IonBackButton,
  ToastController,
} from '@ionic/angular/standalone';
import { play, pause, stop, musicalNotes, checkmarkCircle, refresh } from 'ionicons/icons';
import { addIcons } from 'ionicons';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import type { PlaybackInfo, PlaybackErrorData, PreloadedTrackInfo } from 'capacitor-audio-engine';

@Component({
  selector: 'app-playback',
  templateUrl: './playback.component.html',
  styleUrls: ['./playback.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  standalone: true,
  imports: [
    FormsModule,
    IonContent,
    IonHeader,
    IonTitle,
    IonToolbar,
    IonCard,
    IonCardHeader,
    IonCardTitle,
    IonCardSubtitle,
    IonCardContent,
    IonButton,
    IonIcon,
    IonList,
    IonItem,
    IonLabel,
    IonGrid,
    IonRow,
    IonCol,
    IonProgressBar,
    IonButtons,
    IonBackButton,
  ],
})
export class PlaybackComponent implements OnInit, OnDestroy {
  private readonly toastController = inject(ToastController);
  private readonly router = inject(Router);

  constructor() {
    addIcons({
      play,
      pause,
      stop,
      musicalNotes,
      checkmarkCircle,
      refresh,
    });
  }

  // Playback signals
  protected readonly trackPlaybackStates = signal<Map<string, PlaybackInfo>>(new Map());
  protected readonly preloadedTracks = signal<Set<string>>(new Set());
  protected readonly preloadedTrackInfo = signal<PreloadedTrackInfo[]>([]);
  protected readonly realtimeUpdatesActive = signal(false);

  // Demo playlist
  protected readonly demoPlaylist: Array<{
    id: string;
    url: string;
    title: string;
    artist: string;
  }> = [
    {
      id: 'demo1',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/9479_267163_267163_12_-_Continent_-16-44.1-.aac',
      title: 'Demo Track 1',
      artist: 'Demo Artist',
    },
    {
      id: 'demo2',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/5651_257500_257500_01_-_DuDa_-16-44.1-.aac',
      title: 'Demo Track 2',
      artist: 'Demo Artist',
    },
    {
      id: 'demo3',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/969633_968857_Yarin_Primak_-_bring_it_back_-_IH-000420_-_Master_V2_-_148_Bpm_-_081024_-_BOV_-_ORG_-_2444.aac',
      title: 'Demo Track 3',
      artist: 'Demo Artist',
    },
    {
      id: 'demo4',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/969371_969230_Ran_Haim_Raiten_-_Youre_Welcome_to_Hop_-_AO-002482_-_Master_V1_-_158_Bpm_-_BOV_-_ORG_-_2444.aac',
      title: 'Demo Track 4',
      artist: 'Demo Artist',
    },
  ];

  // Computed signals for UI
  protected readonly hasPreloadedTracks = computed(() => this.preloadedTracks().size > 0);
  protected readonly preloadedTracksCount = computed(() => this.preloadedTracks().size);

  // Helper methods
  isTrackPreloaded(url: string): boolean {
    return this.preloadedTracks().has(url);
  }

  isTrackPlaying(url: string): boolean {
    const state = this.trackPlaybackStates().get(url);
    return state?.isPlaying || false;
  }

  getTrackPosition(url: string): number {
    const state = this.trackPlaybackStates().get(url);
    return state?.currentPosition || 0;
  }

  getTrackDuration(url: string): number {
    const state = this.trackPlaybackStates().get(url);
    return state?.duration || 0;
  }

  getTrackProgress(url: string): number {
    const position = this.getTrackPosition(url);
    const duration = this.getTrackDuration(url);
    return duration > 0 ? position / duration : 0;
  }

  getTrackTitle(url: string): string {
    return this.demoPlaylist.find(t => t.url === url)?.title || 'Unknown Track';
  }

  getCurrentlyPlayingTrack(): string {
    const playingTrack = this.demoPlaylist.find(track => this.isTrackPlaying(track.url));
    return playingTrack?.title || 'None';
  }

  // Lifecycle
  async ngOnInit(): Promise<void> {
    this.preloadAllTracks();
  }

  async ngOnDestroy(): Promise<void> {
    try {
      await CapacitorAudioEngine.destroyPlayback();
      console.log('✅ Playback cleanup complete');
    } catch (error) {
      console.error('Error during playback cleanup:', error);
    }
  }

  // Event Listeners Setup
  private setupPlaybackEventListeners(): void {
    CapacitorAudioEngine.addListener('playbackStatusChanged', event => {
      console.log('Playback status changed:', event);
    });

    CapacitorAudioEngine.addListener('playbackError', async (event: PlaybackErrorData) => {
      console.error('Playback error:', event.message);
    });

    // Listen for real-time progress updates
    CapacitorAudioEngine.addListener('playbackProgress', event => {
      // Indicate that real-time updates are working
      this.realtimeUpdatesActive.set(true);

      // Update track-specific playback info in real-time without API call
      if (event.url) {
        this.trackPlaybackStates.update(states => {
          const newStates = new Map(states);
          const currentState = newStates.get(event.url) || {};
          const track = this.demoPlaylist.find(t => t.url === event.url);

          newStates.set(event.url, {
            ...currentState,
            currentPosition: event.currentPosition,
            duration: event.duration,
            isPlaying: event.isPlaying,
            currentTrack: track ? { id: track.id, url: track.url } : null,
            currentIndex: 0,
          } as PlaybackInfo);

          return newStates;
        });
      }
    });
  }

  // Playback Control Methods
  async preloadTrack(url: string): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: [url],
      });
      if (result.tracks[0]?.loaded) {
        this.preloadedTracks.update(tracks => {
          const newTracks = new Set(tracks);
          newTracks.add(url);
          return newTracks;
        });
      }

      const track = this.demoPlaylist.find(t => t.url === url);
      const trackInfo = result.tracks.find(t => t.url === url);

      if (trackInfo?.loaded) {
        await this.showToast(
          `Preloaded: ${track?.title || 'Track'} (${trackInfo.mimeType || 'Unknown format'})`,
          'success'
        );
      } else {
        await this.showToast(`Failed to preload: ${track?.title || 'Track'}`, 'warning');
      }
    } catch (error) {
      console.error('Failed to preload track:', error);
      await this.showToast('Failed to preload track', 'danger');
    }
  }

  async preloadAllTracks(): Promise<void> {
    try {
      const trackUrls = this.demoPlaylist.map(track => track.url);
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: trackUrls,
      });

      console.log('🎵 Playback component initialized');

      // Update preloaded tracks set with successfully loaded tracks
      const successfulUrls = result.tracks.filter(track => track.loaded).map(track => track.url);
      this.preloadedTracks.set(new Set(successfulUrls));

      // Store detailed track information
      this.preloadedTrackInfo.set(result.tracks);

      this.setupPlaybackEventListeners();
    } catch (error) {
      console.error('Failed to preload all tracks:', error);
      await this.showToast('Failed to preload tracks', 'danger');
    }
  }

  async playTrack(url: string): Promise<void> {
    try {
      // Preload if not already preloaded
      if (!this.isTrackPreloaded(url)) {
        await this.preloadTrack(url);
      }

      await CapacitorAudioEngine.playTrack({ url });
      await this.updateTrackPlaybackInfo(url);

      const track = this.demoPlaylist.find(t => t.url === url);
      await this.showToast(`Playing: ${track?.title || 'Track'}`, 'success');
    } catch (error) {
      console.error('Failed to play track:', error);
      await this.showToast('Failed to play track', 'danger');
    }
  }

  async pauseTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.pauseTrack({ url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to pause track:', error);
      await this.showToast('Failed to pause track', 'danger');
    }
  }

  async stopTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.stopTrack({ url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to stop track:', error);
      await this.showToast('Failed to stop track', 'danger');
    }
  }

  async seekTrack(url: string, seconds: number): Promise<void> {
    try {
      await CapacitorAudioEngine.seekTrack({ url, seconds });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to seek track:', error);
    }
  }

  // Helper method to update track playback info
  private async updateTrackPlaybackInfo(url: string): Promise<void> {
    try {
      const playbackInfo = await CapacitorAudioEngine.getPlaybackInfo();

      this.trackPlaybackStates.update(states => {
        const newStates = new Map(states);
        newStates.set(url, playbackInfo);
        return newStates;
      });
    } catch (error) {
      console.error('Failed to get track playback info:', error);
    }
  }

  // Refresh all track states
  async refreshAllTrackStates(): Promise<void> {
    for (const url of Array.from(this.preloadedTracks())) {
      try {
        await this.updateTrackPlaybackInfo(url);
      } catch (error) {
        console.error(`Failed to refresh state for ${url}:`, error);
      }
    }
  }

  // Utility methods
  protected formatTime(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  private async showToast(message: string, color: 'success' | 'warning' | 'danger' = 'success') {
    const toast = await this.toastController.create({
      header: 'Playback',
      message,
      buttons: [
        {
          text: 'Close',
          role: 'cancel',
        },
      ],
      color,
    });
    await toast.present();
  }
}
