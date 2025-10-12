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
import { TitleCasePipe, DecimalPipe, JsonPipe, DatePipe, UpperCasePipe } from '@angular/common';
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
  IonItemDivider,
  IonLabel,
  IonChip,
  IonGrid,
  IonRow,
  IonCol,
  IonNote,
  IonCheckbox,
  IonRange,
  IonSelect,
  IonSelectOption,
  IonAccordionGroup,
  IonAccordion,
  IonProgressBar,
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
  pulse,
  analytics,
  radioButtonOn,
  radioButtonOff,
  cloudDownload,
} from 'ionicons/icons';
import { addIcons } from 'ionicons';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import type {
  AudioFileInfo,
  PlaybackInfo,
  PlaybackErrorData,
  PreloadedTrackInfo,
  WaveLevelData,
} from 'capacitor-audio-engine';
import { IntelligentWaveformComponent } from '../components/intelligent-waveform.component';
import { Filesystem } from '@capacitor/filesystem';
interface AudioFileInfoWithMetadata extends AudioFileInfo {
  isSegmentRolled?: boolean;
  segmentCount?: number;
  maxDurationSeconds?: number;
}

// Waveform configuration enums and constants
const WaveformBarsCount = {
  BARS_16: 16,
  BARS_32: 32,
  BARS_64: 64,
  BARS_128: 128,
  BARS_256: 256,
} as const;

const WaveformDebounceTime = {
  REALTIME: 0.02, // Real-time visualization (20ms)
  VERY_FAST: 0.05, // Very fast updates (50ms)
  FAST: 0.1, // Fast updates (100ms)
  MEDIUM: 0.25, // Medium updates (250ms)
  SLOW: 0.5, // Slow updates (500ms)
  VERY_SLOW: 1.0, // Very slow updates (1000ms) - Default
} as const;

const SpeechThreshold = {
  VERY_SENSITIVE: 0.005, // Very sensitive (0.005)
  SENSITIVE: 0.01, // Sensitive (0.01)
  NORMAL: 0.02, // Normal (0.02)
  MODERATE: 0.04, // Moderate (0.04)
  LESS_SENSITIVE: 0.06, // Less sensitive (0.06)
  NOT_SENSITIVE: 0.1, // Not sensitive (0.1)
} as const;

const VADWindowSize = {
  MINIMAL: 3, // Minimum latency (~150ms)
  LOW: 4, // Low latency (~200ms)
  NORMAL: 5, // Normal latency (~250ms)
  MEDIUM: 8, // Medium latency (~400ms)
  HIGH: 10, // High accuracy (~500ms)
  MAXIMUM: 15, // Maximum accuracy (~750ms)
} as const;

const CalibrationDuration = {
  QUICK: 500, // Quick calibration (500ms)
  NORMAL: 1000, // Normal calibration (1000ms)
  EXTENDED: 2000, // Extended calibration (2000ms)
  LONG: 3000, // Long calibration (3000ms)
} as const;

@Component({
  selector: 'app-features-demo',
  templateUrl: './features-demo.component.html',
  styleUrls: ['./features-demo.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    TitleCasePipe,
    DecimalPipe,
    JsonPipe,
    DatePipe,
    UpperCasePipe,
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
    IonItemDivider,
    IonLabel,
    IonChip,
    IonGrid,
    IonRow,
    IonCol,
    IonNote,
    IonCheckbox,
    IonRange,
    IonSelect,
    IonSelectOption,
    IonAccordionGroup,
    IonAccordion,
    IonProgressBar,
    IntelligentWaveformComponent,
  ],
})
export class FeaturesDemoComponent implements OnInit, OnDestroy {
  private readonly toastController = inject(ToastController);
  private readonly alertController = inject(AlertController);
  private readonly router = inject(Router);

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
      pulse,
      analytics,
      radioButtonOn,
      radioButtonOff,
      cloudDownload,
    });
  }

  // Recording status signals

  // Permission signals
  protected readonly hasPermission = signal(false);
  protected readonly permissionChecked = signal(false);
  protected readonly microphonePermissionStatus = signal<string>('unknown');
  protected readonly notificationPermissionStatus = signal<string>('unknown');
  protected readonly detailedPermissionInfo = signal<any>(null);

  // Segment rolling signals
  protected readonly isSegmentRollingEnabled = signal(false);
  protected readonly maxDurationSeconds = signal<number | undefined>(undefined);

  // Microphone signals
  protected readonly currentMicrophone = signal<number | null>(null);
  protected readonly microphoneBusy = signal(false);

  // Waveform data signals - growing waveform history with enum support
  protected readonly waveformHistory = signal<number[]>([]);
  protected readonly waveformBarsCount = signal(WaveformBarsCount.BARS_128);
  protected readonly waveformDebounceTime = signal(WaveformDebounceTime.MEDIUM);
  protected readonly waveformEnabled = signal(true);

  protected readonly maxWaveformLevel = signal(0);

  // Legacy support for existing controls
  protected readonly waveformBars = computed(() => this.waveformBarsCount());
  protected readonly waveformIntervalSeconds = computed(() => this.waveformDebounceTime());

  // Speech detection configuration signals with enum support
  protected readonly speechDetectionEnabled = signal(false);
  protected readonly speechThreshold = signal(SpeechThreshold.MODERATE);
  protected readonly speechCalibrationDuration = signal(CalibrationDuration.NORMAL);

  // Advanced VAD configuration signals with enum support
  protected readonly vadEnabled = signal(true);
  protected readonly vadWindowSize = signal(VADWindowSize.NORMAL);
  protected readonly voiceBandFilterEnabled = signal(true);
  protected readonly vadDebugMode = signal(false);

  // Legacy support for existing controls
  protected readonly useVAD = computed(() => this.vadEnabled());
  protected readonly advancedVADEnabled = computed(() => this.vadEnabled());
  protected readonly calibrationDuration = computed(() => this.speechCalibrationDuration());

  // Configuration options for dropdowns
  protected readonly waveformBarsOptions = [
    { value: WaveformBarsCount.BARS_16, label: '16 Bars' },
    { value: WaveformBarsCount.BARS_32, label: '32 Bars' },
    { value: WaveformBarsCount.BARS_64, label: '64 Bars' },
    { value: WaveformBarsCount.BARS_128, label: '128 Bars (Default)' },
    { value: WaveformBarsCount.BARS_256, label: '256 Bars' },
  ];

  protected readonly speechThresholdOptions = [
    { value: SpeechThreshold.VERY_SENSITIVE, label: 'Very Sensitive (0.005)' },
    { value: SpeechThreshold.SENSITIVE, label: 'Sensitive (0.01)' },
    { value: SpeechThreshold.NORMAL, label: 'Normal (0.02)' },
    { value: SpeechThreshold.MODERATE, label: 'Moderate (0.04)' },
    { value: SpeechThreshold.LESS_SENSITIVE, label: 'Less Sensitive (0.06)' },
    { value: SpeechThreshold.NOT_SENSITIVE, label: 'Not Sensitive (0.1)' },
  ];

  protected readonly calibrationDurationOptions = [
    { value: CalibrationDuration.QUICK, label: 'Quick (500ms)' },
    { value: CalibrationDuration.NORMAL, label: 'Normal (1000ms)' },
    { value: CalibrationDuration.EXTENDED, label: 'Extended (2000ms)' },
    { value: CalibrationDuration.LONG, label: 'Long (3000ms)' },
  ];

  // Continuous emission tracking
  protected readonly silenceDetected = signal(false);
  protected readonly lastEmissionTime = signal<number>(0);
  protected readonly totalEmissions = signal(0);
  protected readonly silenceEmissions = signal(0);

  // Audio files signals
  protected readonly recordedFiles = signal<AudioFileInfoWithMetadata[]>([]);
  protected readonly selectedAudioInfo = signal<AudioFileInfoWithMetadata | null>(null);
  protected readonly filesWithInfo = signal<Set<string>>(new Set());

  // Trimming signals
  protected readonly trimLastSeconds = signal<number>(0);
  protected readonly showTrimOptions = signal<boolean>(false);

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

  // Demo playlist for per-URL playback (simplified)
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

  // UI signals
  protected readonly activeTab = signal<
    'recording' | 'playback' | 'microphones' | 'audio-info' | 'waveform' | 'intelligent-waveform'
  >('recording');

  // Option arrays for button groups (using enum values)
  // For usage with TypeScript, import and use:
  // import { AudioSampleRate, AudioChannels, AudioBitrate } from 'capacitor-audio-engine';
  protected readonly sampleRateOptions = [
    { value: 8000, label: '8k', enum: 'VOICE_8K' }, // AudioSampleRate.VOICE_8K
    { value: 16000, label: '16k', enum: 'VOICE_16K' }, // AudioSampleRate.VOICE_16K
    { value: 22050, label: '22k', enum: 'STANDARD_22K' }, // AudioSampleRate.STANDARD_22K
    { value: 44100, label: '44k', enum: 'CD_44K' }, // AudioSampleRate.CD_44K
    { value: 48000, label: '48k', enum: 'HIGH_48K' }, // AudioSampleRate.HIGH_48K
  ];

  protected readonly channelOptions = [
    { value: 1, label: 'Mono', enum: 'MONO' }, // AudioChannels.MONO
    { value: 2, label: 'Stereo', enum: 'STEREO' }, // AudioChannels.STEREO
  ];

  protected readonly bitrateOptions = [
    { value: 16000, label: '16k', enum: 'VERY_LOW' }, // AudioBitrate.VERY_LOW
    { value: 32000, label: '32k', enum: 'LOW' }, // AudioBitrate.LOW
    { value: 64000, label: '64k', enum: 'MEDIUM' }, // AudioBitrate.MEDIUM
    { value: 128000, label: '128k', enum: 'HIGH' }, // AudioBitrate.HIGH
    { value: 256000, label: '256k', enum: 'VERY_HIGH' }, // AudioBitrate.VERY_HIGH
  ];

  protected readonly maxDurationOptions = [
    { value: 5, label: '5s' },
    { value: 30, label: '30s' },
    { value: 60, label: '1m' },
    { value: 120, label: '2m' },
    { value: 300, label: '5m' },
    { value: 600, label: '10m' },
    { value: 900, label: '15m' },
    { value: 1200, label: '20m' },
    { value: 1800, label: '30m' },
  ];

  protected readonly trimOptions = [
    { value: 0, label: 'No trim' },
    { value: 5, label: 'Last 5s' },
    { value: 10, label: 'Last 10s' },
    { value: 15, label: 'Last 15s' },
    { value: 30, label: 'Last 30s' },
    { value: 60, label: 'Last 1m' },
  ];

  // Recording guards
  protected readonly canStartRecording = computed(
    () =>
      this.hasPermission() &&
      (this.recordingStatus() === 'idle' || this.recordingStatus() === 'stopped')
  );
  protected readonly canPauseRecording = computed(() => this.recordingStatus() === 'recording');
  protected readonly canResumeRecording = computed(() => this.recordingStatus() === 'paused');
  protected readonly canStopRecording = computed(() =>
    ['recording', 'paused'].includes(this.recordingStatus())
  );
  protected readonly canResetRecording = computed(() =>
    new Set(['paused', 'recording']).has(this.recordingStatus())
  );
  protected readonly canGetRecordingStatus = computed(() => this.recordingStatus() !== 'idle');

  protected readonly recordingProgressPercent = computed(() => {
    if (!this.isSegmentRollingEnabled()) return 0;
    const maxDuration = this.maxDurationSeconds();
    const currentDuration = this.recordingDuration();
    if (!maxDuration || maxDuration <= 0) return 0;
    return Math.min((currentDuration / maxDuration) * 100, 100);
  });

  protected readonly formattedDuration = computed(() => this.formatTime(this.recordingDuration()));

  protected readonly effectiveDuration = computed(() => {
    const duration = this.recordingDuration();
    const trimLast = this.trimLastSeconds();

    if (trimLast > 0 && duration > trimLast) {
      return this.formatTime(trimLast);
    }
    return this.formatTime(duration);
  });

  // Waveform computed signals
  protected readonly normalizedWaveformHistory = computed(() => {
    const levels = this.waveformHistory();
    const maxLevel = this.maxWaveformLevel();
    if (maxLevel === 0) return levels;
    return levels.map((level: number) => level / maxLevel);
  });

  protected readonly waveformVisualizationData = computed(() => {
    const levels = this.normalizedWaveformHistory();
    const bars = this.waveformBars();

    // For growing waveform, we want to show the most recent levels
    // If we have more levels than bars, show the last N bars
    if (levels.length >= bars) {
      return levels.slice(-bars);
    } else {
      // If we have fewer levels than bars, pad with zeros at the beginning
      const padding = new Array(bars - levels.length).fill(0);
      return [...padding, ...levels];
    }
  }); // Per-URL Playback computed signals
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
  isTrackPreloaded(url: string): boolean {
    return this.preloadedTracks().has(url);
  }

  getPreloadedTrackInfo(url: string): PreloadedTrackInfo | null {
    return this.preloadedTrackInfo().find(info => info.url === url) || null;
  }

  getTrackTitleByUrl(url: string): string {
    return this.demoPlaylist.find(t => t.url === url)?.title || 'Unknown Track';
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

  // Recording computed properties
  protected readonly formattedRecordingDuration = computed(() =>
    this.formatTime(this.recordingDuration())
  );
  protected readonly recordingWaveLevelPercent = computed(() =>
    Math.round(this.recordingWaveLevel() * 100)
  );
  protected readonly recordingWaveLevelVisualization = computed(() => {
    const history = this.recordingWaveLevelHistory();
    const maxBars = 50; // Limit visualization to 50 bars
    if (history.length <= maxBars) return history;
    return history.slice(-maxBars);
  });

  // Recording signals
  protected readonly recordingStatus = signal<'idle' | 'recording' | 'paused' | 'stopped'>('idle');
  protected readonly recordingEndedAt = signal<number | null>(null);
  protected readonly recordingDetails = signal<{
    status: string;
    endedAt?: number;
    mimeType?: string;
    path?: string;
  } | null>(null);
  protected readonly recordingFilePath = signal<string | null>(null);
  protected readonly recordingMimeType = signal<string | null>(null);
  protected readonly recordingEncoding = signal<'aac' | 'opus' | 'pcm16' | null>(null);

  // Last recorded audio file info
  protected readonly lastRecordedAudioFile = signal<AudioFileInfo | null>(null);
  protected readonly showRecordedFileActions = signal(false);

  // Recording duration and wave level signals
  protected readonly recordingDuration = signal(0);
  protected readonly recordingWaveLevel = signal(0);
  protected readonly recordingWaveLevelHistory = signal<number[]>([]);
  protected readonly recordingMaxWaveLevel = signal(0);
  protected readonly recordingMaxWaveLevelComputed = computed(() =>
    Math.round(this.recordingMaxWaveLevel() * 100)
  );

  // Recording methods
  async checkPermission(): Promise<void> {
    try {
      // Try new detailed permissions first, fallback to legacy if not available
      try {
        const result = await CapacitorAudioEngine.checkPermissions();
        this.hasPermission.set(result.granted);
        this.permissionChecked.set(true);
        this.detailedPermissionInfo.set(result);

        // Extract individual permission statuses
        this.microphonePermissionStatus.set(result.status || 'unknown');
        this.notificationPermissionStatus.set(result.status || 'unknown');
      } catch (detailedError) {
        // Fallback to legacy permission check
        console.log('Using legacy permission check');
        const result = await CapacitorAudioEngine.checkPermissions();
        this.hasPermission.set(result.granted);
        this.permissionChecked.set(true);
      }
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

  async checkMicrophonePermission(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.checkPermissionMicrophone();
      this.microphonePermissionStatus.set(result.status);
      await this.showToast(
        `Microphone: ${result.status}`,
        result.status === 'granted' ? 'success' : 'warning'
      );
    } catch (error) {
      console.error('Microphone permission check failed:', error);
      await this.showToast(
        'Microphone permission check not available - using legacy method.',
        'warning'
      );
      // Fallback to checking overall permissions
      await this.checkPermission();
    }
  }

  async checkNotificationPermission(): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.checkPermissionNotifications();
      this.notificationPermissionStatus.set(result.status);
      await this.showToast(
        `Notifications: ${result.status}`,
        result.status === 'granted' ? 'success' : 'warning'
      );
    } catch (error) {
      console.error('Notification permission check failed:', error);
      await this.showToast(
        'Notification permission check not available - using legacy method.',
        'warning'
      );
      // Fallback to checking overall permissions
      await this.checkPermission();
    }
  }

  async requestPermission(): Promise<void> {
    try {
      // Try new detailed permissions first, fallback to legacy if not available
      try {
        const result = await CapacitorAudioEngine.requestPermissions();
        this.hasPermission.set(result.granted);
        this.detailedPermissionInfo.set(result);

        // Extract individual permission statuses
        this.microphonePermissionStatus.set(result.status || 'unknown');
        this.notificationPermissionStatus.set(result.status || 'unknown');

        if (!result.granted) {
          await this.showToast('Permission denied. Cannot record audio.', 'warning');
        } else {
          await this.showToast('All permissions granted!', 'success');
        }
      } catch (detailedError) {
        // Fallback to legacy permission request
        console.log('Using legacy permission request');
        const result = await CapacitorAudioEngine.requestPermissions();
        this.hasPermission.set(result.granted);
        if (!result.granted) {
          await this.showToast('Permission denied. Cannot record audio.', 'warning');
        } else {
          await this.showToast('Permission granted!', 'success');
        }
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

  // Per-URL Playback methods
  async preloadTrack(url: string): Promise<void> {
    try {
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: [url],
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
        this.setupWaveformEventListeners();
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
      this.setupWaveformEventListeners();
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

  async resumeTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.resumeTrack({ url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to resume track:', error);
      await this.showToast('Failed to resume track', 'danger');
    }
  }

  async stopTrack(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.stopTrack({ url });
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
      await CapacitorAudioEngine.seekTrack({ seconds, url });
      await this.updateTrackPlaybackInfo(url);
    } catch (error) {
      console.error('Failed to seek track:', error);
      await this.showToast('Failed to seek', 'danger');
    }
  }

  onSeekChange(event: CustomEvent, url: string): void {
    // Only seek if this is a user interaction (knobMoveEnd), not a programmatic update
    // This prevents excessive seeking during playback progress updates
    const eventType = event.type;

    // Only respond to knobMoveEnd events (when user releases the slider)
    // or when explicitly called from user interaction
    if (eventType !== 'ionKnobMoveEnd' && eventType !== 'ionChange') {
      return;
    }

    const percentage = event.detail.value;
    const duration = this.getTrackDuration(url);
    const seekTime = (percentage / 100) * duration;

    if (duration > 0) {
      console.log(`Seeking to ${seekTime}s (${percentage}%) in track: ${url}`);
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
    CapacitorAudioEngine.addListener('playbackError', async (event: PlaybackErrorData) => {
      console.error('Playback error:', event.message);
      await this.showToast(`Playback error: ${event.message}`, 'danger');
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

        // Also update recorded audio playback info if this is the current recorded file
        if (this.currentRecordedFile() && event.url === this.currentRecordedFile()!.uri) {
          this.recordedAudioPlaybackInfo.set({
            currentPosition: event.currentPosition,
            duration: event.duration,
            isPlaying: event.isPlaying,
            currentTrack: { id: 'recorded', url: event.url },
            currentIndex: 0,
          } as PlaybackInfo);
        }
      }
    });
  }

  private setupWaveformEventListeners(): void {
    // Listen for waveform data
    CapacitorAudioEngine.addListener('waveLevel', (event: WaveLevelData) => {
      // Track emission statistics
      const currentTime = Date.now();
      this.lastEmissionTime.set(currentTime);
      this.totalEmissions.update(count => count + 1);
      // Add the new level to the growing history (including silence levels)
      this.waveformHistory.update(history => [...history, event.level]);
    });
  }

  // Recorded audio playback methods
  async preloadRecordedAudio(file: AudioFileInfoWithMetadata): Promise<void> {
    try {
      console.log('🚀 ~ preloadRecordedAudio ~ file.uri:', file.uri);

      // Use the file URI directly with the new preloadTracks interface
      const result = await CapacitorAudioEngine.preloadTracks({
        tracks: [file.uri],
      });

      console.log('🚀 ~ preloadRecordedAudio ~ result:', result);

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
    } catch (error) {
      console.error('Failed to preload recorded audio:', error);
      await this.showToast('Failed to preload audio', 'danger');
    }
  }

  async playRecordedAudio(file?: AudioFileInfoWithMetadata): Promise<void> {
    try {
      console.log('🚀 ~ playRecordedAudio ~ file:', file);
      console.log('🚀 ~ playRecordedAudio ~ file?.uri:', file?.uri);

      // If file is provided and different from current, preload it first
      if (file && (!this.currentRecordedFile() || this.currentRecordedFile()?.uri !== file.uri)) {
        console.log('🚀 ~ playRecordedAudio ~ preloading file with URI:', file.uri);
        await this.preloadRecordedAudio(file);
      }

      // If no playlist is initialized, preload the first available file
      if (!this.recordedPlaylistInitialized() && this.recordedFiles().length > 0) {
        console.log('🚀 ~ playRecordedAudio ~ preloading first available file');
        await this.preloadRecordedAudio(this.recordedFiles()[0]);
      }

      console.log('🚀 ~ playRecordedAudio ~ calling playAudio with URL:', file?.uri || '');
      await CapacitorAudioEngine.playTrack({ url: file?.uri || '' });
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to play recorded audio:', error);
      await this.showToast('Failed to play recorded audio', 'danger');
    }
  }

  async pauseRecordedAudio(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.pauseTrack({ url });
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to pause recorded audio:', error);
      await this.showToast('Failed to pause recorded audio', 'danger');
    }
  }

  async stopRecordedAudio(url: string): Promise<void> {
    try {
      await CapacitorAudioEngine.stopTrack({ url });
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to stop recorded audio:', error);
      await this.showToast('Failed to stop recorded audio', 'danger');
    }
  }

  async seekRecordedAudio(seconds: number): Promise<void> {
    try {
      await CapacitorAudioEngine.seekTrack({ seconds });
      await this.updateRecordedPlaybackInfo();
    } catch (error) {
      console.error('Failed to seek recorded audio:', error);
      await this.showToast('Failed to seek', 'danger');
    }
  }

  onRecordedSeekChange(event: CustomEvent): void {
    // Only seek if this is a user interaction (knobMoveEnd), not a programmatic update
    // This prevents excessive seeking during playback progress updates
    const eventType = event.type;

    // Only respond to knobMoveEnd events (when user releases the slider)
    // or when explicitly called from user interaction
    if (eventType !== 'ionKnobMoveEnd' && eventType !== 'ionChange') {
      return;
    }

    const percentage = event.detail.value;
    const duration = this.recordedTrackDuration();
    const seekTime = (percentage / 100) * duration;

    if (duration > 0) {
      console.log(`Seeking recorded audio to ${seekTime}s (${percentage}%)`);
      this.seekRecordedAudio(seekTime);
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

  hasFileInfo(fileUri: string): boolean {
    return this.filesWithInfo().has(fileUri);
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
  async selectTab(
    tab:
      | 'recording'
      | 'playback'
      | 'microphones'
      | 'audio-info'
      | 'waveform'
      | 'intelligent-waveform'
  ): Promise<void> {
    if (
      tab &&
      [
        'recording',
        'playback',
        'microphones',
        'audio-info',
        'waveform',
        'intelligent-waveform',
      ].includes(tab)
    ) {
      // Navigate to dedicated playback page
      if (tab === 'playback') {
        console.log('🎵 Navigating to dedicated playback page...');
        await this.router.navigate(['/playback']);
        return;
      }

      this.activeTab.set(tab);
    }
  }

  updateMaxDuration(value: number): void {
    this.maxDurationSeconds.set(value);
  }

  updateTrimLastSeconds(value: number): void {
    this.trimLastSeconds.set(value);
  }

  toggleTrimOptions(): void {
    this.showTrimOptions.update(show => !show);
  }

  getTrimPreview(): string {
    const duration = this.recordingDuration();
    const trimLast = this.trimLastSeconds();

    if (trimLast > 0 && duration > trimLast) {
      const start = duration - trimLast;
      return `Will trim from ${this.formatTime(start)} to ${this.formatTime(duration)} (${this.formatTime(trimLast)} total)`;
    }
    return 'No trimming applied';
  }

  // Unified waveform configuration method
  async configureUnifiedWaveform(): Promise<void> {
    try {
      await CapacitorAudioEngine.configureWaveform({
        EmissionInterval: 200,
      });
    } catch (error) {
      console.error('Error configuring unified waveform:', error);
      await this.showToast(
        `Error configuring unified waveform: ${error instanceof Error ? error.message : 'Unknown error'}`,
        'danger'
      );
    }
  }

  async toggleWaveformEnabled(): Promise<void> {
    const enabled = !this.waveformEnabled();
    this.waveformEnabled.set(enabled);
    await this.configureUnifiedWaveform();
  }

  async updateWaveformBars(bars: number): Promise<void> {
    await this.configureUnifiedWaveform();
  }

  async updateWaveformDebounceTime(debounceInSeconds: number): Promise<void> {
    await this.configureUnifiedWaveform();
  }

  async destroyWaveform(): Promise<void> {
    try {
      await CapacitorAudioEngine.destroyWaveform();
      await this.showToast('Waveform configuration destroyed', 'success');
    } catch (error) {
      console.error('Error destroying waveform:', error);
      await this.showToast(
        `Error destroying waveform: ${error instanceof Error ? error.message : 'Unknown error'}`,
        'danger'
      );
    }
  }

  resetWaveformState(): void {
    // Reset all waveform-related state to initial values
    this.waveformHistory.set([]);
    this.maxWaveformLevel.set(0);
    this.totalEmissions.set(0);
    this.silenceEmissions.set(0);
    this.silenceDetected.set(false);
    this.lastEmissionTime.set(0);
    console.log('Waveform state reset');
  }

  // Speech detection configuration methods
  async toggleSpeechDetection(): Promise<void> {
    const enabled = !this.speechDetectionEnabled();
    this.speechDetectionEnabled.set(enabled);
    await this.configureUnifiedWaveform();
  }

  async toggleAdvancedVAD(): Promise<void> {
    const enabled = !this.vadEnabled();
    this.vadEnabled.set(enabled);
    await this.configureUnifiedWaveform();
  }

  updateVadWindowSize(value: number | { detail: { value: number } }): void {
    if (this.vadEnabled()) {
      this.configureUnifiedWaveform();
    }
  }

  async toggleVoiceBandFilter(): Promise<void> {
    const enabled = !this.voiceBandFilterEnabled();
    this.voiceBandFilterEnabled.set(enabled);
    if (this.advancedVADEnabled()) {
      await this.configureUnifiedWaveform();
    }
  }

  async toggleVadDebugMode(): Promise<void> {
    const enabled = !this.vadDebugMode();
    this.vadDebugMode.set(enabled);
    if (this.advancedVADEnabled()) {
      await this.configureUnifiedWaveform();
    }
  }

  async updateSpeechThreshold(threshold: number): Promise<void> {
    if (this.speechDetectionEnabled()) {
      await this.configureUnifiedWaveform();
    }
  }

  async toggleVAD(): Promise<void> {
    const useVAD = !this.vadEnabled();
    this.vadEnabled.set(useVAD);
    if (this.speechDetectionEnabled()) {
      await this.configureUnifiedWaveform();
    }
  }

  async updateCalibrationDuration(duration: number): Promise<void> {
    if (this.speechDetectionEnabled()) {
      await this.configureUnifiedWaveform();
    }
  }

  resetWaveformLevels(): void {
    this.waveformHistory.set([]);
    this.maxWaveformLevel.set(0);
    this.totalEmissions.set(0);
    this.silenceEmissions.set(0);
    this.silenceDetected.set(false);
    this.lastEmissionTime.set(0);
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

  async removeAllFiles(): Promise<void> {
    this.recordedFiles.set([]);
  }

  // Recording methods
  async startRecording(): Promise<void> {
    if (!this.hasPermission()) {
      await this.requestPermission();
      if (!this.hasPermission()) return;
    }
    try {
      const path = `/audio-files/recording_${Date.now()}.m4a`;
      await CapacitorAudioEngine.startRecording({
        path,
      });

      const encoding = 'aac' as const;
      const mime = 'audio/aac';

      this.recordingFilePath.set(path);

      this.recordingMimeType.set(mime);
      this.recordingEncoding.set(encoding);

      this.recordingStatus.set('recording');
      this.recordingEndedAt.set(null);
      this.recordingDetails.set({
        status: 'recording',
        mimeType: mime,
        path: this.recordingFilePath() || (undefined as any),
      });

      // Reset recording monitoring signals
      this.recordingDuration.set(0);
      this.recordingWaveLevel.set(0);
      this.recordingWaveLevelHistory.set([]);
      this.recordingMaxWaveLevel.set(0);
      await this.showToast(`Recording started (${encoding.toUpperCase()})`, 'success');
      this.setupRecordingEventListeners();
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
    } catch (error) {
      console.error('Failed to resume recording:', error);
      await this.showToast('Failed to resume recording', 'danger');
    }
  }

  async stopRecording(): Promise<void> {
    try {
      const audioFileInfo = await CapacitorAudioEngine.stopRecording();
      this.recordingStatus.set('stopped');
      const ended = Date.now();
      this.recordingEndedAt.set(ended);
      this.recordingDetails.update(d => ({
        status: 'stopped',
        endedAt: ended,
        mimeType: audioFileInfo.mimeType,
        path: audioFileInfo.path,
      }));

      // Store the audio file info
      this.lastRecordedAudioFile.set(audioFileInfo);
      this.showRecordedFileActions.set(true);

      // Reset recording monitoring signals
      this.recordingDuration.set(0);
      this.recordingWaveLevel.set(0);
      this.recordingWaveLevelHistory.set([]);
      this.recordingMaxWaveLevel.set(0);

      // Get file info and register the recorded file
      await this.registerRecordedFile();

      await this.showToast('Recording stopped - File saved', 'success');
    } catch (error) {
      console.error('Failed to stop recording:', error);
      await this.showToast('Failed to stop recording', 'danger');
    }
  }

  async getRecordingStatus(): Promise<void> {
    try {
      const status = await CapacitorAudioEngine.getRecordingStatus();

      // Update local signals with the status information
      this.recordingDuration.set(status.duration);

      // Show detailed status information
      const message = [
        `Status: ${status.status.toUpperCase()}`,
        `Duration: ${this.formatTime(status.duration)}`,
        status.path ? `Path: ${status.path}` : null,
      ]
        .filter(Boolean)
        .join('\n');

      // Show alert with detailed information
      const alert = await this.alertController.create({
        header: 'Recording Status',
        message: message.replace(/\n/g, '<br>'),
        buttons: ['OK'],
      });

      await alert.present();
    } catch (error) {
      console.error('Failed to get recording status:', error);
      await this.showToast('Failed to get recording status', 'danger');
    }
  }

  async resetRecording(): Promise<void> {
    try {
      await CapacitorAudioEngine.resetRecording();
      // Reflect paused state with zeroed counters locally
      this.recordingStatus.set('paused');
      this.recordingDuration.set(0);
      this.recordingWaveLevel.set(0);
      this.recordingWaveLevelHistory.set([]);
      this.recordingMaxWaveLevel.set(0);
      await this.showToast('Recording reset - ready to resume', 'success');
    } catch (error) {
      console.error('Failed to reset recording:', error);
      await this.showToast('Failed to reset recording', 'danger');
    }
  }

  async trimRecordedAudio(): Promise<void> {
    const audioFile = this.lastRecordedAudioFile();
    if (!audioFile) {
      await this.showToast('No recorded audio file available', 'warning');
      return;
    }

    // Show prompt to get trim times
    const alert = await this.alertController.create({
      header: 'Trim Audio',
      message: `Duration: ${audioFile.duration.toFixed(2)}s`,
      inputs: [
        {
          name: 'startTime',
          type: 'number',
          placeholder: 'Start time (seconds)',
          value: '0',
          min: 0,
          max: audioFile.duration,
        },
        {
          name: 'endTime',
          type: 'number',
          placeholder: 'End time (seconds)',
          value: audioFile.duration.toFixed(2),
          min: 0,
          max: audioFile.duration,
        },
      ],
      buttons: [
        {
          text: 'Cancel',
          role: 'cancel',
        },
        {
          text: 'Trim',
          handler: async data => {
            const startTime = parseFloat(data.startTime) || 0;
            const endTime = parseFloat(data.endTime) || audioFile.duration;

            if (startTime < 0 || endTime <= startTime || endTime > audioFile.duration) {
              await this.showToast('Invalid trim times', 'danger');
              return;
            }

            try {
              const trimmedFile = await CapacitorAudioEngine.trimAudio({
                uri: audioFile.uri,
                startTime,
                endTime,
              });

              await this.showToast(`Audio trimmed: ${trimmedFile.duration.toFixed(2)}s`, 'success');

              // Update the last recorded file to the trimmed version
              this.lastRecordedAudioFile.set(trimmedFile);

              // Register the trimmed file
              this.recordedFiles.update(files => [
                ...files,
                { ...trimmedFile, isSegmentRolled: false },
              ]);
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

  dismissRecordedFileActions(): void {
    this.showRecordedFileActions.set(false);
  }

  private async registerRecordedFile(): Promise<void> {
    try {
      // Use the audio file info returned from stopRecording
      const audioFileInfo = this.lastRecordedAudioFile();
      if (!audioFileInfo || !audioFileInfo.uri) {
        console.warn('No recording file info available for registration');
        return;
      }

      const file = await Filesystem.readFile({
        path: audioFileInfo.uri,
      });

      try {
        const results = await CapacitorAudioEngine.preloadTracks({
          tracks: [audioFileInfo.uri],
        });
        if (!results.tracks || results.tracks.length === 0) {
          return;
        }
      } catch (preloadError) {
        console.error('🚀 ~ registerRecordedFile ~ preload error:', preloadError);
        throw preloadError;
      }

      const recordedFile: AudioFileInfoWithMetadata = {
        ...audioFileInfo,
        isSegmentRolled: false,
      };

      this.recordedFiles.update(files => [...files, recordedFile]);
      this.currentRecordedFile.set(recordedFile);
      this.recordedPlaylistInitialized.set(false);

      // Mark this file as having info fetched
      this.filesWithInfo.update(files => new Set([...Array.from(files), audioFileInfo.uri]));
    } catch (e) {
      console.error('Failed to register recorded file', e);
    }
  }

  private setupRecordingEventListeners(): void {
    CapacitorAudioEngine.addListener('recordingStatusChanged', event => {
      const status = event?.status as 'idle' | 'recording' | 'paused' | 'stopped';
      if (status) {
        this.recordingStatus.set(status);
        if (status === 'stopped') {
          const ended = Date.now();
          this.recordingEndedAt.set(ended);
          this.recordingDetails.update(d => ({
            status,
            endedAt: ended,
            mimeType: d?.mimeType,
            path: d?.path,
          }));
        }
      }
    });

    // Listen for duration changes during recording
    CapacitorAudioEngine.addListener('durationChange', event => {
      if (event?.duration !== undefined) {
        this.recordingDuration.set(event.duration);
      }
    });

    // Listen for wave level changes during recording
    CapacitorAudioEngine.addListener('waveLevel', event => {
      if (event?.level !== undefined) {
        const level = event.level as number;
        this.recordingWaveLevel.set(level);

        // Update wave level history
        this.recordingWaveLevelHistory.update(history => {
          const newHistory = [...history, level];
          // Keep only last 100 levels to prevent memory issues
          return newHistory.length > 100 ? newHistory.slice(-100) : newHistory;
        });

        // Update max wave level
        this.recordingMaxWaveLevel.update(max => Math.max(max, level));
      }
    });
  }

  // Lifecycle
  async ngOnInit(): Promise<void> {
    await this.checkPermission();
    this.setupPlaybackEventListeners();
    this.setupWaveformEventListeners();
    this.setupRecordingEventListeners();
  }

  ngOnDestroy(): void {
    // Clean up any remaining listeners
    CapacitorAudioEngine.removeAllListeners();
  }
}
