import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TitleCasePipe } from '@angular/common';
import {
  IonContent,
  IonHeader,
  IonTitle,
  IonToolbar,
  IonCard,
  IonCardHeader,
  IonCardTitle,
  IonCardContent,
  IonButton,
  IonIcon,
  IonList,
  IonItem,
  IonItemGroup,
  IonItemDivider,
  IonLabel,
  IonProgressBar,
  IonChip,
  IonGrid,
  IonRow,
  IonCol,
  IonSegment,
  IonSegmentButton,
  IonNote,
  IonCheckbox,
  IonRange,
  AlertController,
  ToastController,
} from '@ionic/angular/standalone';
import {
  micCircle,
  micOffCircle,
  volumeHigh,
  settings,
  informationCircle,
  refresh,
  cut,
  time,
  play,
  pause,
  stop,
  playSkipForward,
  playSkipBack,
  musicalNotes,
  download,
  checkmarkCircle,
  closeCircle,
} from 'ionicons/icons';
import { addIcons } from 'ionicons';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import type {
  AudioFileInfo,
  RecordingOptions,
  MicrophoneInfo,
  RecordingStatus,
  AudioTrack,
  PlaybackInfo,
  PlaybackStatus,
  TrackChangedData,
  TrackEndedData,
  PlaybackStartedData,
  PlaybackPausedData,
  ErrorEventData,
  PreloadedTrackInfo,
} from 'capacitor-audio-engine';

// Extended interface for demo to track segment rolling metadata
interface AudioFileInfoWithMetadata extends AudioFileInfo {
  isSegmentRolled?: boolean;
  segmentCount?: number;
  maxDurationSeconds?: number;
}

@Component({
  selector: 'app-features-demo',
  templateUrl: './features-demo.component.html',
  styleUrls: ['./features-demo.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TitleCasePipe,
    IonContent,
    IonHeader,
    IonTitle,
    IonToolbar,
    IonCard,
    IonCardHeader,
    IonCardTitle,
    IonCardContent,
    IonButton,
    IonIcon,
    IonList,
    IonItem,
    IonItemGroup,
    IonItemDivider,
    IonLabel,
    IonProgressBar,
    IonChip,
    IonGrid,
    IonRow,
    IonCol,
    IonSegment,
    IonSegmentButton,
    IonNote,
    IonCheckbox,
    IonRange,
  ],
})
export class FeaturesDemoComponent {
  private readonly toastController = inject(ToastController);
  private readonly alertController = inject(AlertController);

  constructor() {
    addIcons({
      micCircle,
      micOffCircle,
      volumeHigh,
      settings,
      informationCircle,
      refresh,
      cut,
      time,
      play,
      pause,
      stop,
      playSkipForward,
      playSkipBack,
      musicalNotes,
      download,
      checkmarkCircle,
      closeCircle,
    });
  }

  // Recording status signals
  protected readonly recordingStatus = signal<RecordingStatus>('idle');
  protected readonly recordingDuration = signal(0);

  // Permission signals
  protected readonly hasPermission = signal(false);
  protected readonly permissionChecked = signal(false);

  // Recording options signals
  protected readonly recordingOptions = signal<RecordingOptions>({
    sampleRate: 44100,
    channels: 1,
    bitrate: 128000,
  });

  // Segment rolling signals
  protected readonly isSegmentRollingEnabled = signal(false);
  protected readonly maxDurationSeconds = signal<number | undefined>(undefined);

  // Microphone signals
  protected readonly availableMicrophones = signal<MicrophoneInfo[]>([]);
  protected readonly currentMicrophone = signal<number | null>(null);
  protected readonly microphoneBusy = signal(false);

  // Audio files signals
  protected readonly recordedFiles = signal<AudioFileInfoWithMetadata[]>([]);
  protected readonly selectedAudioInfo = signal<AudioFileInfoWithMetadata | null>(null);

  // Per-URL Playback signals
  protected readonly trackPlaybackStates = signal<Map<string, PlaybackInfo>>(new Map());
  protected readonly preloadedTracks = signal<Set<string>>(new Set());
  protected readonly preloadedTrackInfo = signal<PreloadedTrackInfo[]>([]);
  protected readonly realtimeUpdatesActive = signal(false);

  // Recorded audio playback signals
  protected readonly recordedAudioPlaybackInfo = signal<PlaybackInfo | null>(null);
  protected readonly recordedPlaylistInitialized = signal(false);
  protected readonly currentRecordedFile = signal<AudioFileInfoWithMetadata | null>(null);
  protected readonly preloadedFiles = signal<Set<string>>(new Set());

  // Demo playlist for per-URL playback
  protected readonly demoPlaylist: AudioTrack[] = [
    {
      id: 'demo1',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/9479_267163_267163_12_-_Continent_-16-44.1-.aac',
      title: 'Demo Track 1',
      artist: 'Demo Artist',
      artworkUrl: 'https://via.placeholder.com/300x300/FF6B6B/FFFFFF?text=Track+1',
    },
    {
      id: 'demo2',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/5651_257500_257500_01_-_DuDa_-16-44.1-.aac',
      title: 'Demo Track 2',
      artist: 'Demo Artist',
      artworkUrl: 'https://via.placeholder.com/300x300/4ECDC4/FFFFFF?text=Track+2',
    },
    {
      id: 'demo3',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/969633_968857_Yarin_Primak_-_bring_it_back_-_IH-000420_-_Master_V2_-_148_Bpm_-_081024_-_BOV_-_ORG_-_2444.aac',
      title: 'Demo Track 3',
      artist: 'Demo Artist',
      artworkUrl: 'https://via.placeholder.com/300x300/45B7D1/FFFFFF?text=Track+3',
    },
    {
      id: 'demo4',
      url: 'https://cms-public-artifacts.artlist.io/content/music/aac/969371_969230_Ran_Haim_Raiten_-_Youre_Welcome_to_Hop_-_AO-002482_-_Master_V1_-_158_Bpm_-_BOV_-_ORG_-_2444.aac',
      title: 'Demo Track 4',
      artist: 'Demo Artist',
      artworkUrl: 'https://via.placeholder.com/300x300/FF9F43/FFFFFF?text=Track+4',
    },
  ];

  // UI signals
  protected readonly activeTab = signal<'recording' | 'playback' | 'microphones' | 'audio-info'>(
    'recording'
  );

  // Option arrays for button groups
  protected readonly sampleRateOptions = [
    { value: 8000, label: '8k' },
    { value: 16000, label: '16k' },
    { value: 22050, label: '22k' },
    { value: 44100, label: '44k' },
    { value: 48000, label: '48k' },
  ];

  protected readonly channelOptions = [
    { value: 1, label: 'Mono' },
    { value: 2, label: 'Stereo' },
  ];

  protected readonly bitrateOptions = [
    { value: 64000, label: '64k' },
    { value: 96000, label: '96k' },
    { value: 128000, label: '128k' },
    { value: 192000, label: '192k' },
    { value: 256000, label: '256k' },
    { value: 320000, label: '320k' },
  ];

  protected readonly maxDurationOptions = [
    { value: 5, label: '5s' },
    { value: 30, label: '30s' },
    { value: 60, label: '1m' },
    { value: 120, label: '2m' },
    { value: 300, label: '5m' },
    { value: 600, label: '10m' },
  ];

  // Computed signals
  protected readonly canRecord = computed(
    () => this.hasPermission() && this.recordingStatus() === 'idle'
  );
  protected readonly canPause = computed(() => this.recordingStatus() === 'recording');
  protected readonly canResume = computed(() => this.recordingStatus() === 'paused');
  protected readonly canStop = computed(() =>
    ['recording', 'paused'].includes(this.recordingStatus())
  );

  protected readonly recordingProgressPercent = computed(() => {
    if (!this.isSegmentRollingEnabled()) return 0;
    const maxDuration = this.maxDurationSeconds();
    const currentDuration = this.recordingDuration();
    if (!maxDuration || maxDuration <= 0) return 0;
    return Math.min((currentDuration / maxDuration) * 100, 100);
  });

  protected readonly formattedDuration = computed(() => this.formatTime(this.recordingDuration()));

  // Per-URL Playback computed signals
  protected readonly hasPreloadedTracks = computed(() => this.preloadedTracks().size > 0);

  // Helper methods for track-specific state
  getTrackPlaybackInfo(url: string): PlaybackInfo | null {
    return this.trackPlaybackStates().get(url) || null;
  }

  isTrackPlaying(url: string): boolean {
    return this.getTrackPlaybackInfo(url)?.isPlaying || false;
  }

  getTrackPosition(url: string): number {
    return this.getTrackPlaybackInfo(url)?.currentPosition || 0;
  }

  getTrackDuration(url: string): number {
    return this.getTrackPlaybackInfo(url)?.duration || 0;
  }

  getTrackProgress(url: string): number {
    const position = this.getTrackPosition(url);
    const duration = this.getTrackDuration(url);
    return duration > 0 ? (position / duration) * 100 : 0;
  }

  getFormattedTrackTime(url: string): string {
    return `${this.formatTime(this.getTrackPosition(url))} / ${this.formatTime(this.getTrackDuration(url))}`;
  }

  isTrackPreloaded(url: string): boolean {
    return this.preloadedTracks().has(url);
  }

  getPreloadedTrackInfo(url: string): PreloadedTrackInfo | null {
    return this.preloadedTrackInfo().find(info => info.url === url) || null;
  }

  getTrackTitleByUrl(url: string): string {
    return this.demoPlaylist.find(t => t.url === url)?.title || 'Unknown Track';
  }

  // Helper methods for template complex operations
  hasAnyTrackPlaying(): boolean {
    return this.demoPlaylist.some(track => this.isTrackPlaying(track.url));
  }

  getCurrentlyPlayingTrack(): string {
    const playingTrack = this.demoPlaylist.find(track => this.isTrackPlaying(track.url));
    return playingTrack?.title || 'None';
  }

  // Recorded audio playback computed signals
  protected readonly isRecordedAudioPlaying = computed(
    () => this.recordedAudioPlaybackInfo()?.isPlaying || false
  );
  protected readonly recordedPlaybackPosition = computed(
    () => this.recordedAudioPlaybackInfo()?.currentPosition || 0
  );
  protected readonly recordedTrackDuration = computed(
    () => this.recordedAudioPlaybackInfo()?.duration || 0
  );
  protected readonly recordedPlaybackProgress = computed(() => {
    const position = this.recordedPlaybackPosition();
    const duration = this.recordedTrackDuration();
    return duration > 0 ? (position / duration) * 100 : 0;
  });
  protected readonly formattedRecordedPlaybackTime = computed(
    () =>
      `${this.formatTime(this.recordedPlaybackPosition())} / ${this.formatTime(this.recordedTrackDuration())}`
  );

  // Recording methods
  async checkPermission(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.checkPermission();
      this.hasPermission.set(result.granted);
      this.permissionChecked.set(true);
    } catch (error) {
      console.error('Permission check failed:', error);
      this.hasPermission.set(false);
      this.permissionChecked.set(true);
    }
    if (!this.hasPermission()) {
      await this.showToast('Permission denied. Cannot record audio.', 'danger');
    } else {
      await this.showToast('Permission granted. You can record audio.', 'success');
    }
  }

  async requestPermission(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.requestPermission();
      this.hasPermission.set(result.granted);
      if (!result.granted) {
        await this.showToast('Permission denied. Cannot record audio.', 'warning');
      }
    } catch (error) {
      console.error('Permission request failed:', error);
      await this.showToast('Permission request failed.', 'danger');
    }
  }

  async openSettings(): Promise<void> {
    try {
      await CapacitorAudioEngine.openSettings();
      await this.showToast('Opening app permissions...', 'success');
    } catch (error) {
      console.error('Failed to open app settings:', error);
      await this.showToast('Failed to open app permissions', 'danger');
    }
  }

  async startRecording(): Promise<void> {
    if (!this.hasPermission()) {
      await this.requestPermission();
      if (!this.hasPermission()) return;
    }

    try {
      const options = this.recordingOptions();
      const segmentOptions = this.isSegmentRollingEnabled()
        ? { maxDuration: this.maxDurationSeconds() }
        : {};

      await CapacitorAudioEngine.startRecording({
        ...options,
        ...segmentOptions,
      });

      this.recordingStatus.set('recording');
      await this.showToast('Recording started', 'success');
      this.setupRecordingEventListeners();
      this.startDurationTimer();
    } catch (error) {
      console.error('Failed to start recording:', error);
      await this.showToast('Failed to start recording', 'danger');
    }
  }

  async pauseRecording(): Promise<void> {
    try {
      await CapacitorAudioEngine.pauseRecording();
      this.recordingStatus.set('paused');
      await this.showToast('Recording paused', 'warning');
    } catch (error) {
      console.error('Failed to pause recording:', error);
      await this.showToast('Failed to pause recording', 'danger');
    }
  }

  async resumeRecording(): Promise<void> {
    try {
      await CapacitorAudioEngine.resumeRecording();
      this.recordingStatus.set('recording');
      await this.showToast('Recording resumed', 'success');
      this.startDurationTimer();
    } catch (error) {
      console.error('Failed to resume recording:', error);
      await this.showToast('Failed to resume recording', 'danger');
    }
  }

  async stopRecording(): Promise<void> {
    this.recordingStatus.set('idle');
    this.recordingDuration.set(0);

    try {
      const result = await CapacitorAudioEngine.stopRecording();

      // Add metadata for segment rolling demo
      const fileWithMetadata: AudioFileInfoWithMetadata = {
        ...result,
        isSegmentRolled: this.isSegmentRollingEnabled(),
        maxDurationSeconds: this.maxDurationSeconds(),
      };

      this.recordedFiles.update(files => [...files, fileWithMetadata]);
      await this.showToast('Recording stopped and saved', 'success');
    } catch (error) {
      console.error('Failed to stop recording:', error);
      // Only show toast if it's not a "no active recording" error (which can happen with multiple clicks)
      if (!error?.toString().includes('No active recording to stop')) {
        await this.showToast('Failed to stop recording', 'danger');
      }
    }
  }

  // Microphone methods
  async loadAvailableMicrophones(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.getAvailableMicrophones();
      this.availableMicrophones.set(result.microphones);
    } catch (error) {
      console.error('Failed to load microphones:', error);
      await this.showToast('Failed to load microphones', 'danger');
    }
  }

  async checkMicrophoneStatus(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.isMicrophoneBusy();
      this.microphoneBusy.set(result.busy);
    } catch (error) {
      console.error('Failed to check microphone status:', error);
      await this.showToast('Failed to check microphone status', 'danger');
    }
  }

  async switchMicrophone(microphoneId: number): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.switchMicrophone({ microphoneId });
      if (result.success) {
        this.currentMicrophone.set(result.microphoneId);
        await this.showToast('Microphone switched successfully', 'success');
      } else {
        await this.showToast('Failed to switch microphone', 'warning');
      }
    } catch (error) {
      console.error('Failed to switch microphone:', error);
      await this.showToast('Failed to switch microphone', 'danger');
    }
  }

  // Per-URL Playback methods
  async preloadTrack(url: string): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: [url],
        preloadNext: false,
      });

      // Update preloaded tracks set
      this.preloadedTracks.update(tracks => new Set([...Array.from(tracks), url]));

      // Store detailed track information
      this.preloadedTrackInfo.update(info => {
        const existingInfo = info.filter(item => item.url !== url);
        return [...existingInfo, ...result.tracks];
      });

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

      // Initialize event listeners if this is the first preloaded track
      if (this.preloadedTracks().size === 1) {
        this.setupPlaybackEventListeners();
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
        preloadNext: true,
      });

      // Update preloaded tracks set with successfully loaded tracks
      const successfulUrls = result.tracks.filter(track => track.loaded).map(track => track.url);
      this.preloadedTracks.set(new Set(successfulUrls));

      // Store detailed track information
      this.preloadedTrackInfo.set(result.tracks);

      const successCount = result.tracks.filter(track => track.loaded).length;
      const totalCount = result.tracks.length;

      if (successCount === totalCount) {
        await this.showToast('All demo tracks preloaded successfully', 'success');
      } else {
        await this.showToast(
          `${successCount}/${totalCount} tracks preloaded successfully`,
          'warning'
        );
      }

      // Set up event listeners
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

      await CapacitorAudioEngine.playAudio({ url });
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
      await CapacitorAudioEngine.pauseAudio({ url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to pause track:', error);
      await this.showToast('Failed to pause track', 'danger');
    }
  }

  async resumeTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.resumeAudio({ url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to resume track:', error);
      await this.showToast('Failed to resume track', 'danger');
    }
  }

  async stopTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.stopAudio({ url });
      await this.updateTrackPlaybackInfo(url);

      // Clear playback state for this track
      this.trackPlaybackStates.update(states => {
        const newStates = new Map(states);
        newStates.delete(url);
        return newStates;
      });
    } catch (error) {
      console.error('Failed to stop track:', error);
      await this.showToast('Failed to stop track', 'danger');
    }
  }

  async seekTrack(url: string, seconds: number): Promise<void> {
    try {
      await CapacitorAudioEngine.seekAudio({ seconds, url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to seek track:', error);
      await this.showToast('Failed to seek', 'danger');
    }
  }

  onProgressBarClick(event: MouseEvent, url: string): void {
    const target = event.target as HTMLElement;
    const progressBar = target.closest('ion-progress-bar');

    if (progressBar) {
      const rect = progressBar.getBoundingClientRect();
      const clickX = event.clientX - rect.left;
      const percentage = Math.min(Math.max(clickX / rect.width, 0), 1);
      const duration = this.getTrackDuration(url);
      const seekTime = percentage * duration;

      if (duration > 0) {
        this.seekTrack(url, seekTime);
      }
    }
  }

  onSeekChange(event: any, url: string): void {
    const percentage = event.detail.value;
    const duration = this.getTrackDuration(url);
    const seekTime = (percentage / 100) * duration;

    if (duration > 0) {
      this.seekTrack(url, seekTime);
    }
  }

  private async updateTrackPlaybackInfo(url: string): Promise<void> {
    try {
      const info = await CapacitorAudioEngine.getPlaybackInfo();

      // Update the specific track's playback state
      this.trackPlaybackStates.update(states => {
        const newStates = new Map(states);
        newStates.set(url, info);
        return newStates;
      });
    } catch (error) {
      console.error('Failed to get track playback info:', error);
    }
  }

  private setupPlaybackEventListeners(): void {
    CapacitorAudioEngine.addListener('trackChanged', async (event: TrackChangedData) => {
      console.log('Track changed to:', event.track.title);
      if (event.track?.url) {
        await this.updateTrackPlaybackInfo(event.track.url);
      }
      await this.showToast(`Now playing: ${event.track.title}`, 'success');
    });

    CapacitorAudioEngine.addListener('trackEnded', async (event: TrackEndedData) => {
      console.log('Track ended:', event.track.title);
      if (event.track?.url) {
        await this.updateTrackPlaybackInfo(event.track.url);
      }
    });

    CapacitorAudioEngine.addListener('playbackStarted', async (event: PlaybackStartedData) => {
      console.log('Playback started:', event.track.title);
      if (event.track?.url) {
        await this.updateTrackPlaybackInfo(event.track.url);
      }
    });

    CapacitorAudioEngine.addListener('playbackPaused', async (event: PlaybackPausedData) => {
      console.log('Playback paused:', event.track.title);
      if (event.track?.url) {
        await this.updateTrackPlaybackInfo(event.track.url);
      }
    });

    CapacitorAudioEngine.addListener('playbackError', async (event: ErrorEventData) => {
      console.error('Playback error:', event.message);
      await this.showToast(`Playback error: ${event.message}`, 'danger');
    });

    // Listen for real-time progress updates
    CapacitorAudioEngine.addListener('playbackProgress' as any, (event: any) => {
      console.log(`Progress: ${event.currentPosition}/${event.duration} seconds`);

      // Indicate that real-time updates are working
      this.realtimeUpdatesActive.set(true);

      // Update track-specific playback info in real-time without API call
      if (event.track?.url) {
        this.trackPlaybackStates.update(states => {
          const newStates = new Map(states);
          const currentState = newStates.get(event.track.url) || {};

          newStates.set(event.track.url, {
            ...currentState,
            currentPosition: event.currentPosition,
            duration: event.duration,
            isPlaying: event.isPlaying,
            currentTrack: event.track,
            currentIndex: 0,
            status: event.isPlaying ? 'playing' : 'paused',
          });

          return newStates;
        });
      }
    });

    // Listen for status changes
    CapacitorAudioEngine.addListener('playbackStatusChanged' as any, (event: any) => {
      console.log(`Status changed to: ${event.status}, Playing: ${event.isPlaying}`);

      // Update track-specific playback info in real-time without API call
      if (event.track?.url) {
        this.trackPlaybackStates.update(states => {
          const newStates = new Map(states);

          newStates.set(event.track.url, {
            currentTrack: event.track,
            currentIndex: event.index,
            currentPosition: event.currentPosition,
            duration: event.duration,
            isPlaying: event.isPlaying,
            status: event.status as PlaybackStatus,
          });

          return newStates;
        });
      }
    });
  }

  // Recorded audio playback methods
  async preloadRecordedAudio(file: AudioFileInfoWithMetadata): Promise<void> {
    try {
      // Use the file URI directly with the new preloadTracks interface
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: [file.uri],
        preloadNext: false,
      });

      const trackInfo = result.tracks.find(t => t.url === file.uri);

      if (trackInfo?.loaded) {
        this.recordedPlaylistInitialized.set(true);
        this.currentRecordedFile.set(file);

        // Small delay to ensure Android player is fully initialized
        await new Promise(resolve => setTimeout(resolve, 100));

        await this.updateRecordedPlaybackInfo();
        await this.showToast(
          `Preloaded: ${file.filename} (${trackInfo.mimeType || 'Unknown format'})`,
          'success'
        );

        // Mark as preloaded
        this.preloadedFiles.update(files => new Set([...Array.from(files), file.uri]));
      } else {
        await this.showToast(`Failed to preload: ${file.filename}`, 'warning');
      }
    } catch (error: any) {
      console.error('Failed to preload recorded audio:', error);
      await this.showToast('Failed to preload audio', 'danger');
    }
  }

  async playRecordedAudio(file?: AudioFileInfoWithMetadata): Promise<void> {
    try {
      // If file is provided and different from current, preload it first
      if (file && (!this.currentRecordedFile() || this.currentRecordedFile()?.uri !== file.uri)) {
        await this.preloadRecordedAudio(file);
      }

      // If no playlist is initialized, preload the first available file
      if (!this.recordedPlaylistInitialized() && this.recordedFiles().length > 0) {
        await this.preloadRecordedAudio(this.recordedFiles()[0]);
      }

      await CapacitorAudioEngine.playAudio();
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to play recorded audio:', error);
      await this.showToast('Failed to play recorded audio', 'danger');
    }
  }

  async pauseRecordedAudio(): Promise<void> {
    try {
      await CapacitorAudioEngine.pauseAudio();
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to pause recorded audio:', error);
      await this.showToast('Failed to pause recorded audio', 'danger');
    }
  }

  async stopRecordedAudio(): Promise<void> {
    try {
      await CapacitorAudioEngine.stopAudio();
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to stop recorded audio:', error);
      await this.showToast('Failed to stop recorded audio', 'danger');
    }
  }

  async seekRecordedAudio(seconds: number): Promise<void> {
    try {
      await CapacitorAudioEngine.seekAudio({ seconds });
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to seek recorded audio:', error);
      await this.showToast('Failed to seek', 'danger');
    }
  }

  private async updateRecordedPlaybackInfo(): Promise<void> {
    try {
      const info = await CapacitorAudioEngine.getPlaybackInfo();
      this.recordedAudioPlaybackInfo.set(info);
    } catch (error) {
      console.error('Failed to get recorded playback info:', error);
      // Don't show toast for this error as it might be temporary during initialization
      // The method will be called again when user interacts with playback controls
    }
  }

  isFilePreloaded(fileUri: string): boolean {
    return this.preloadedFiles().has(fileUri);
  }

  isCurrentlyPlaying(file: AudioFileInfoWithMetadata): boolean {
    return this.currentRecordedFile()?.uri === file.uri && this.isRecordedAudioPlaying();
  }

  // Audio trimming
  async trimAudio(file: AudioFileInfoWithMetadata): Promise<void> {
    const alert = await this.alertController.create({
      header: 'Trim Audio',
      message: `Trim "${file.filename}" (Duration: ${this.formatTime(file.duration)})`,
      inputs: [
        {
          name: 'startTime',
          type: 'number',
          placeholder: 'Start time (seconds)',
          min: 0,
          max: file.duration,
          value: 0,
        },
        {
          name: 'endTime',
          type: 'number',
          placeholder: 'End time (seconds)',
          min: 0,
          max: file.duration,
          value: file.duration,
        },
      ],
      buttons: [
        { text: 'Cancel', role: 'cancel' },
        {
          text: 'Trim',
          handler: async data => {
            try {
              const start = parseFloat(data.startTime) || 0;
              const end = parseFloat(data.endTime) || file.duration;

              if (start >= end) {
                await this.showToast('Invalid time range', 'warning');
                return;
              }

              const result = await CapacitorAudioEngine.trimAudio({
                uri: file.uri,
                start,
                end,
              });
              console.log('🚀 ~ FeaturesDemoComponent ~ trimAudio ~ result:', result);

              const trimmedFile: AudioFileInfoWithMetadata = {
                ...result,
                isSegmentRolled: false,
              };

              this.recordedFiles.update(files => [...files, trimmedFile]);
              await this.showToast('Audio trimmed successfully', 'success');
            } catch (error) {
              console.error('Failed to trim audio:', error);
              await this.showToast('Failed to trim audio', 'danger');
            }
          },
        },
      ],
    });

    await alert.present();
  }

  // Utility methods
  private durationTimer: any;
  private durationChangeListener: any;

  private setupRecordingEventListeners(): void {
    // Remove any existing listener first
    if (this.durationChangeListener) {
      this.durationChangeListener.remove();
    }

    // Set up duration change listener for real-time recording duration updates
    this.durationChangeListener = CapacitorAudioEngine.addListener(
      'durationChange',
      (event: { duration: number }) => {
        console.log('Duration updated:', event.duration);
        this.recordingDuration.set(event.duration);
      }
    );
  }

  private async startDurationTimer(): Promise<void> {
    // Primary method: Use event listener for real-time duration updates
    // This is set up in setupRecordingEventListeners() when recording starts

    // Fallback timer with longer interval since we have real-time events
    try {
      await CapacitorAudioEngine.addListener('durationChange', event => {
        console.log('🚀 ~ FeaturesDemoComponent ~ startDurationTimer ~ event:', event);
        console.log('Duration updated:', event.duration);
        this.recordingDuration.set(event.duration);
      });
    } catch (error) {
      console.error('Failed to get duration:', error);
    }
  }

  protected formatTime(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes.toString().padStart(2, '0')}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  protected formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  // Tab navigation
  selectTab(tab: any): void {
    if (tab && ['recording', 'playback', 'microphones', 'audio-info'].includes(tab)) {
      this.activeTab.set(tab);

      if (tab === 'microphones') {
        this.loadAvailableMicrophones();
        this.checkMicrophoneStatus();
      } else if (tab === 'playback') {
        // Refresh playback info for all preloaded tracks
        this.refreshAllTrackStates();
      }
    }
  }

  // Helper method to refresh all track states
  private async refreshAllTrackStates(): Promise<void> {
    for (const url of Array.from(this.preloadedTracks())) {
      try {
        await this.updateTrackPlaybackInfo(url);
      } catch (error) {
        console.error('Failed to refresh track state:', error);
      }
    }
  }

  // Recording options update methods
  updateSampleRate(value: number): void {
    this.recordingOptions.update(opts => ({ ...opts, sampleRate: value }));
  }

  updateChannels(value: number): void {
    this.recordingOptions.update(opts => ({ ...opts, channels: value }));
  }

  updateBitrate(value: number): void {
    this.recordingOptions.update(opts => ({ ...opts, bitrate: value }));
  }

  updateMaxDuration(value: number): void {
    this.maxDurationSeconds.set(value);
  }

  // Toast helper
  private async showToast(
    message: string,
    color: 'success' | 'warning' | 'danger' = 'success'
  ): Promise<void> {
    const toast = await this.toastController.create({
      message,
      duration: 2000,
      color,
      position: 'bottom',
    });
    await toast.present();
  }

  // Lifecycle
  async ngOnInit(): Promise<void> {
    await this.checkPermission();
  }

  ngOnDestroy(): void {
    // Clean up any remaining listeners
    if (this.durationChangeListener) {
      this.durationChangeListener.remove();
      this.durationChangeListener = null;
    }
  }
}
