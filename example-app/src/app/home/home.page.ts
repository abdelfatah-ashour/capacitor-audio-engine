import { Component,  OnInit, signal, OnDestroy, inject } from '@angular/core';
import { IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon, IonRange, IonItem, IonLabel, IonSelect, IonSelectOption, IonCard, IonCardHeader, IonCardTitle, IonCardContent, IonBadge, IonSpinner, IonCheckbox, IonInput, IonCardSubtitle } from '@ionic/angular/standalone';
import { CapacitorAudioEngine,AudioFileInfo, MicrophoneInfo } from "capacitor-audio-engine";
import { CommonModule } from '@angular/common';
import { addIcons } from 'ionicons';
import { playOutline, pauseOutline, stopOutline, micOutline, keyOutline, timeOutline,stopCircleOutline, headsetOutline, phonePortraitOutline, bluetoothOutline, refreshOutline, warningOutline, cutOutline, bugOutline, shieldCheckmarkOutline, volumeHighOutline, repeatOutline, speedometerOutline, playSkipForwardOutline, playSkipBackOutline, informationCircleOutline, cloudOutline, cloudDownloadOutline, volumeLowOutline, playCircleOutline, folderOutline, musicalNotesOutline } from 'ionicons/icons';
import { FormsModule } from '@angular/forms';
import { Capacitor } from '@capacitor/core';
import { AlertController } from '@ionic/angular';
import { Filesystem } from '@capacitor/filesystem';
import { RouterLink } from '@angular/router';



 const urlAudio = "https://cdn.pixabay.com/audio/2025/03/19/audio_91b4c0a3b6.mp3";

const urlAudio2 = "https://cdn.pixabay.com/audio/2025/02/18/audio_67a824edf7.mp3";

const urlAudio3 = "https://cdn.pixabay.com/audio/2024/11/29/audio_45bbd49c34.mp3";

const urlAudio4 = "https://cdn.pixabay.com/audio/2024/11/05/audio_da986d1e2a.mp3";


@Component({
  selector: 'app-home',
  templateUrl: 'home.page.html',
  styleUrls: ['home.page.scss'],
  imports: [CommonModule, FormsModule, RouterLink, IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon,IonRange, IonItem, IonLabel, IonSelect, IonSelectOption, IonCard, IonCardHeader, IonCardTitle, IonCardContent, IonBadge, IonSpinner, IonCheckbox, IonInput, IonCardSubtitle],
  standalone: true
})
export class HomePage implements OnInit, OnDestroy{
  private readonly alertController = inject(AlertController);
  startAudioTime = signal<number>(0);
  endAudioTime = signal<number>(0);
  currentAudioTime = signal<number>(0);

  hasPermission = false;
  isRecording = false;
  hasRecording = false;
  isPaused = false;
  recordingUrl = signal<string>('');
  currentTime = 0;
  duration = 0;
  audioInfo = signal<AudioFileInfo | null>(null);
  currentDuration = signal<number>(0);

  // Microphone management properties
  availableMicrophones = signal<MicrophoneInfo[]>([]);
  selectedMicrophoneId = signal<number | null>(null);
  microphoneBusy = signal<boolean>(false);
  isLoadingMicrophones = signal<boolean>(false);

  // Playback properties
  isPlaying = signal<boolean>(false);
  playbackCurrentTime = signal<number>(0);
  playbackDuration = signal<number>(0);
  playbackSpeed = signal<number>(1.0);
  playbackVolume = signal<number>(1.0);
  isLooping = signal<boolean>(false);

  // CDN Audio Testing properties
  cdnAudioUrls = [
    {
      name: 'Sample MP3 (CDN)',
      url: urlAudio,
      type: 'mp3'
    },
    {
      name: 'Sample WAV (CDN)',
      url:  urlAudio2,
      type: 'wav'
    },
    {
      name: 'Sample M4A (CDN)',
      url:  urlAudio3,
      type: 'm4a'
    },
    {
      name: 'Sample AAC (CDN)',
      url:  urlAudio4,
      type: 'aac'
    }
  ];
  selectedCdnUrl = signal<string>('');
  cdnPreloadStatus = signal<string>('');
  cdnPlaybackStatus = signal<string>('');
  isPreloading = signal<boolean>(false);
  cdnAudioInfo = signal<AudioFileInfo | null>(null);

  // Add channel configuration
  recordingChannels = 1;  // Default to mono
  recordingQualities = {
    voice: {
      channels: 1,
      bitRate: 96000,    // 96kbps
      sampleRate: 44100, // 44.1kHz
      label: 'Voice Optimized (Mono)'
    },
    stereo: {
      channels: 2,
      bitRate: 128000,   // 128kbps
      sampleRate: 44100, // 44.1kHz
      label: 'Stereo Quality'
    }
  };

  private wasRecordingBeforeInterruption = false;

  constructor() {
    addIcons({
      playOutline,
      pauseOutline,
      stopOutline,
      micOutline,
      keyOutline,
      timeOutline,
      stopCircleOutline,
      headsetOutline,
      phonePortraitOutline,
      bluetoothOutline,
      refreshOutline,
      warningOutline,
      cutOutline,
      bugOutline,
      shieldCheckmarkOutline,
      volumeHighOutline,
      repeatOutline,
      speedometerOutline,
      playSkipForwardOutline,
      playSkipBackOutline,
      informationCircleOutline,
      cloudOutline,
      cloudDownloadOutline,
      volumeLowOutline,
      playCircleOutline,
      folderOutline,
      musicalNotesOutline
    });
  }

  ngOnInit(): void {
    this._checkPermission();

    CapacitorAudioEngine.getStatus().then((res) => {
      console.log("🚀 ~ getStatus:", res)
      this.isRecording = res.isRecording;
    }).catch((error) => {
      console.error("Error getting status:", error);
    });

     document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.checkRecordingState();
      }
    });

    // Initialize microphone management
    this.initializeMicrophoneManagement();

    // Start monitoring for interruptions
    this.setupInterruptionListener();

    // Set up duration change listener
    CapacitorAudioEngine.addListener("durationChange", (data) => {
      console.log("🚀 ~ HomePage ~ CapacitorAudioEngine.addListener ~ data:", data.duration)
      this.currentDuration.set(data.duration);
    });

    // Set up playback event listeners
    this.setupPlaybackListeners();
  }

  ngOnDestroy() {
    // Clean up listener when component is destroyed
    CapacitorAudioEngine.removeAllListeners();
  }

  private async setupInterruptionListener() {
    try {
      // First add the listener
      CapacitorAudioEngine.addListener('recordingInterruption', (data) => {
        console.log("🚀 ~ HomePage ~ CapacitorAudioEngine.addListener ~ data:", data.message)
        this.handleRecordingInterruption(data.message);
      });
      console.log('Interruption listener setup complete');
    } catch (error) {
      console.error('Failed to setup interruption listener:', error);
    }
  }

   async checkRecordingState() {
    try {
      const { isRecording: nativeIsRecording } = await CapacitorAudioEngine.getStatus();
      if (this.isRecording !== nativeIsRecording) {
        // If there's a mismatch, stop the recording
        if (this.isRecording) {
          await this.stopRecording();
        }
      }
    } catch (error) {
      console.error('Failed to check recording state:', error);
    }
  }

  private _checkPermission() {
    CapacitorAudioEngine.checkPermission().then(({granted}) => {
      this.hasPermission = granted;
    }).catch((error) => {
      console.error("Error checking permission:", error);
    });
  }

  public checkPermission() {
    CapacitorAudioEngine.checkPermission().then(({granted}) => {
      console.log("🚀 ~ HomePage ~ CapacitorAudioEngine.checkPermission ~ granted:", granted)
      this.alertController.create({
        header: 'Permission Status',
        message: granted ? 'Permission granted' : 'Permission denied',
        buttons: [
          {
            text: 'OK',
            handler: () => {
              if (!granted) {
                this._requestPermission();
              }
            }
          }
        ]
      }).then(alert => alert.present());
    }).catch((error) => {
      console.log("Error checking permission:", error);
    });
  }

  private _requestPermission() {
    CapacitorAudioEngine.requestPermission().then(({granted}) => {
      this.hasPermission = granted;
      this.alertController.create({
        header: 'Permission Request',
        message: granted ? 'Permission granted' : 'Permission denied',
        buttons: ['OK']
      }).then(alert => alert.present());
    });
  }


  formatTime(seconds: number): string {
    if (!seconds || isNaN(seconds)) return '0:00';

    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  async stopRecording() {
    try {
      const res = await CapacitorAudioEngine.stopRecording();
      const readfile = await Filesystem.readFile({
        path: res.uri,
      })

      // Validate the base64 Data URI format
       this.validateBase64Audio(res.base64);
      const file = await Capacitor.convertFileSrc(res.uri);
      this.isRecording = false;
      this.hasRecording = true;

      // update time signals
      const durationInSeconds = res.duration;

      // Initialize the range with start at 0 and end at total duration
      this.duration = durationInSeconds;
      this.startAudioTime.set(0);
      this.endAudioTime.set(this.duration);
      this.currentAudioTime.set(0);
      this.recordingUrl.set(file);
      this.audioInfo.set(res);

      // Don't remove all listeners here, as we need them for the next recording session
      // Instead, we'll clean up in ngOnDestroy
    } catch (error) {
      console.error("Error stopping recording:", error);
      this.isRecording = false;
    }
  }

  handleRangeChange(event: Event) {
    const range = event as CustomEvent;
    const { lower, upper } = range.detail.value;
      // Ensure we don't exceed the total duration
      if (upper <= this.duration) {
        // Only update if start is less than end
        if (lower < upper) {
          this.startAudioTime.set(lower);
          this.endAudioTime.set(upper);
          // Update current time to match start time for preview
          this.currentAudioTime.set(lower);
        } else {
          // If start tries to exceed end, reset to previous values
          this.startAudioTime.set(this.startAudioTime());
          this.endAudioTime.set(this.endAudioTime());
        }
      } else {
        // If end exceeds duration, keep it at duration
        this.endAudioTime.set(this.duration);
      }
  }

  async trimAudio() {
    if (!this.audioInfo()) {
      console.error('No audio info available for trimming.');
      return;
    }

    try {
      const res = await CapacitorAudioEngine.trimAudio({
        uri: this.audioInfo()!.uri,
        start: this.startAudioTime(),
        end: this.endAudioTime()
      });
      console.log("🚀 ~ HomePage ~ trimAudio ~ res:", res)

      const file = Capacitor.convertFileSrc(res.uri);
      // Update state
      this.duration = res.duration;
      this.startAudioTime.set(0);
      this.endAudioTime.set(this.duration);
      this.currentAudioTime.set(0);
      this.recordingUrl.set(file);
      this.audioInfo.set(res); // Keep audioInfo in sync
    } catch (error) {
      console.error("Error trimming audio:", error);
    }
  }

  async handleRecordingInterruption(message: string) {
    console.log('Recording interruption:', message);

    if (message.includes('Interruption began') ||
        message.includes('App moved to background') ||
        message.includes('App entered background')) {
      // Store the recording state before interruption
      this.wasRecordingBeforeInterruption = this.isRecording;

      if (this.isRecording) {
        try {
          await CapacitorAudioEngine.pauseRecording();
          console.log('Recording paused due to interruption');
        } catch (error) {
          console.error('Failed to pause recording:', error);
        }
      }
    }
    else if (message.includes('Interruption ended - should resume') ||
             message.includes('App became active')) {
      // Resume recording if it was active before interruption
      if (this.wasRecordingBeforeInterruption) {
        try {
          await CapacitorAudioEngine.startRecording({maxDuration:5});
          console.log('Recording resumed after interruption');
        } catch (error) {
          console.error('Failed to resume recording:', error);
        }
      }
    }
  }

  async testInterruptionListener() {
    try {
      console.log('Testing interruption listener...');
      await CapacitorAudioEngine.addListener('recordingInterruption', (data: any) => {
        console.log('Test interruption received:', data);
      });
      console.log('Test listener setup complete');
    } catch (error) {
      console.error('Test listener setup failed:', error);
    }
  }

  public async handlePause() {
    await CapacitorAudioEngine.pauseRecording();
    this.isPaused = true;
    this.isRecording = false;
    console.log('Recording paused');
  }

  public async handleResume() {
    await CapacitorAudioEngine.resumeRecording();
    this.isPaused = false;
    this.isRecording = true;
    console.log('Recording resumed');
  }

  // New microphone management methods
  async initializeMicrophoneManagement() {
      await this.loadAvailableMicrophones();
  }

  async loadAvailableMicrophones() {
    this.checkMicrophoneBusy()
    if (Capacitor.isNativePlatform()) {
      this.isLoadingMicrophones.set(true);
      try {
        const result = await CapacitorAudioEngine.getAvailableMicrophones();
        console.log("🚀 ~ HomePage ~ loadAvailableMicrophones ~ result:", result)
        this.availableMicrophones.set(result.microphones);

        // Set default to first internal microphone if none selected
        if (this.selectedMicrophoneId() === null && result.microphones.length > 0) {
          const internalMic = result.microphones.find(mic => mic.type === 'internal');
          if (internalMic) {
            this.selectedMicrophoneId.set(internalMic.id);
          } else {
            this.selectedMicrophoneId.set(result.microphones[0].id);
          }
        }
      } catch (error) {
        console.error('Failed to load microphones:', error);
        this.showAlert('Error', 'Failed to load available microphones');
      } finally {
        this.isLoadingMicrophones.set(false);
      }
    }
  }

  async checkMicrophoneBusy() {
    if (Capacitor.isNativePlatform()) {
      try {
        const result = await CapacitorAudioEngine.isMicrophoneBusy();
        console.log("🚀 ~ HomePage ~ checkMicrophoneBusy ~ result:", result)
        this.microphoneBusy.set(result.busy);
      } catch (error) {
        console.error('Failed to check microphone status:', error);
      }
    }
  }

  async switchMicrophone(microphoneId: number) {
    if (Capacitor.isNativePlatform()) {
      try {
        const result = await CapacitorAudioEngine.switchMicrophone({ microphoneId });
        this.selectedMicrophoneId.set(result.microphoneId);

        const mic = this.availableMicrophones().find(m => m.id === microphoneId);
        this.showAlert('Success', `Switched to ${mic?.name || 'selected microphone'}`);
      } catch (error) {
        console.error('Failed to switch microphone:', error);
        this.showAlert('Error', 'Failed to switch microphone');
      }
    }
  }

  getMicrophoneIcon(type: string): string {
    switch (type) {
      case 'internal':
        return 'phone-portrait-outline';
      case 'external':
        return 'headset-outline';
      default:
        return 'mic-outline';
    }
  }

  getMicrophoneTypeColor(type: string): string {
    switch (type) {
      case 'internal':
        return 'primary';
      case 'external':
        return 'success';
      default:
        return 'medium';
    }
  }

  async showAlert(header: string, message: string) {
    const alert = await this.alertController.create({
      header,
      message,
      buttons: ['OK']
    });
    await alert.present();
  }

  // Override existing methods to include microphone management
  public async startRecording() {
    try {
      // Check microphone status before starting
      await this.checkMicrophoneBusy();

      if (this.microphoneBusy()) {
        this.showAlert('Microphone Busy', 'Another app is using the microphone');
        return;
      }

      // Switch to selected microphone if available
      if (Capacitor.isNativePlatform() && this.selectedMicrophoneId() !== null) {
        await this.switchMicrophone(this.selectedMicrophoneId()!);
      }

      CapacitorAudioEngine.startRecording({maxDuration:5}).then(() => {
        this.isRecording = true;
        this.hasRecording = false;
        this.isPaused = false;
        console.log('Recording started');
      });
    } catch (error) {
      console.error('Failed to start recording:', error);
      this.showAlert('Error', 'Failed to start recording');
    }
  }

  // Override permission request to reload microphones
  public async requestPermission() {
    CapacitorAudioEngine.requestPermission().then(async ({granted}) => {
      this.hasPermission = granted;

      if (granted) {
        await this.initializeMicrophoneManagement();
      }

      this.alertController.create({
        header: 'Permission Request',
        message: granted ? 'Permission granted' : 'Permission denied',
        buttons: ['OK']
      }).then(alert => alert.present());
    });
  }

  // ========== PLAYBACK METHODS ==========

  private async setupPlaybackListeners() {
    try {
      // Listen for playback progress updates
      CapacitorAudioEngine.addListener('playbackProgress', (data) => {
        this.playbackCurrentTime.set(data.currentTime);
        this.playbackDuration.set(data.duration);
      });

      // Listen for playback status changes
      CapacitorAudioEngine.addListener('playbackStatusChange', (data) => {
        this.isPlaying.set(data.status === 'playing');
      });

      // Listen for playback completion
      CapacitorAudioEngine.addListener('playbackCompleted', (data) => {
        this.isPlaying.set(false);
        this.playbackCurrentTime.set(0);
        this.showAlert('Playback Complete', 'Audio playback finished successfully.');
      });

      // Listen for playback errors
      CapacitorAudioEngine.addListener('playbackError', (data) => {
        this.isPlaying.set(false);
        this.showAlert('Playback Error', `Error: ${data.message}`);
      });
      console.log('Playback listeners setup complete');
    } catch (error) {
      console.error('Failed to setup playback listeners:', error);
    }
  }

  async startPlayback() {
    const audioInfo = this.audioInfo();
    if (!audioInfo) {
      this.showAlert('No Recording', 'Please record audio first.');
      return;
    }

    try {
      // Check current playback status
      const status = await CapacitorAudioEngine.getPlaybackStatus();

      if (status.status === 'paused') {
        // If playback is paused, resume it instead of starting new playback
        await CapacitorAudioEngine.resumePlayback();
        console.log('🚀 ~ Playback resumed');
      } else {
        // Start new playback
        await CapacitorAudioEngine.startPlayback({
          uri: audioInfo.uri,
          speed: this.playbackSpeed(),
          volume: this.playbackVolume(),
          loop: this.isLooping(),
          startTime: 0
        });
        console.log('🚀 ~ Playback started');
      }
    } catch (error) {
      console.error('Failed to start/resume playback:', error);
      this.showAlert('Playback Error', `Failed to start/resume playback: ${error}`);
    }
  }

  async pausePlayback() {
    try {
      await CapacitorAudioEngine.pausePlayback();
      console.log('🚀 ~ Playback paused');
    } catch (error) {
      console.error('Failed to pause playback:', error);
      this.showAlert('Playback Error', `Failed to pause playback: ${error}`);
    }
  }

  async resumePlayback() {
    try {
      // Example: Resume with current playback settings
      await CapacitorAudioEngine.resumePlayback({
        speed: this.playbackSpeed(),
        volume: this.playbackVolume(),
        loop: this.isLooping()
      });
      console.log('🚀 ~ Playback resumed with options');
    } catch (error) {
      console.error('Failed to resume playback:', error);
      this.showAlert('Playback Error', `Failed to resume playback: ${error}`);
    }
  }

  async stopPlayback() {
    try {
      await CapacitorAudioEngine.stopPlayback();
      console.log('🚀 ~ Playback stopped');
    } catch (error) {
      console.error('Failed to stop playback:', error);
      this.showAlert('Playback Error', `Failed to stop playback: ${error}`);
    }
  }

  async seekToTime(time: number) {
    try {
      await CapacitorAudioEngine.seekTo({ time });
      console.log(`🚀 ~ Seeked to ${time} seconds`);
    } catch (error) {
      console.error('Failed to seek:', error);
      this.showAlert('Seek Error', `Failed to seek: ${error}`);
    }
  }

  async getPlaybackStatus() {
    try {
      const status = await CapacitorAudioEngine.getPlaybackStatus();
      console.log('🚀 ~ Playback status:', status);
      return status;
    } catch (error) {
      console.error('Failed to get playback status:', error);
      throw error;
    }
  }

  onPlaybackSpeedChange(event: any) {
    this.playbackSpeed.set(event.detail.value);
    console.log('🚀 ~ Playback speed changed to:', this.playbackSpeed());
  }

  onPlaybackVolumeChange(event: any) {
    this.playbackVolume.set(event.detail.value);
    console.log('🚀 ~ Playback volume changed to:', this.playbackVolume());
  }

  toggleLooping() {
    this.isLooping.set(!this.isLooping());
    console.log('🚀 ~ Looping toggled to:', this.isLooping());
  }

  onSeekChange(event: any) {
    const seekTime = event.detail.value;
    this.seekToTime(seekTime);
  }

  // ========== PRELOAD METHODS ==========

  async preloadAudio() {
    const audioInfo = this.audioInfo();
    if (!audioInfo) {
      this.showAlert('No Recording', 'Please record audio first.');
      return;
    }

    try {
      console.log('🚀 ~ Preloading audio...');
     const results = await CapacitorAudioEngine.preload({
        uri: audioInfo.uri,
        prepare: true // Load audio data into memory for faster playback
      });
      console.log("🚀 ~ HomePage ~ preloadAudio ~ results:", results)
      console.log('🚀 ~ Audio preloaded successfully');
      this.showAlert('Preload Complete', 'Audio has been preloaded and is ready for instant playback.');
    } catch (error) {
      console.error('Failed to preload audio:', error);
      this.showAlert('Preload Error', `Failed to preload audio: ${error}`);
    }
  }

  async preloadWithoutPrepare() {
    const audioInfo = this.audioInfo();
    if (!audioInfo) {
      this.showAlert('No Recording', 'Please record audio first.');
      return;
    }

    try {
      console.log('🚀 ~ Preloading audio (without prepare)...');
     const results = await CapacitorAudioEngine.preload({
        uri: audioInfo.uri,
        prepare: false // Just set up player without loading data
      });
      console.log("🚀 ~ HomePage ~ preloadWithoutPrepare ~ results:", results)
      console.log('🚀 ~ Audio player setup completed');
      this.showAlert('Player Setup Complete', 'Audio player is ready (data not preloaded).');
    } catch (error) {
      console.error('Failed to setup audio player:', error);
      this.showAlert('Setup Error', `Failed to setup audio player: ${error}`);
    }
  }

  // Base64 validation utility for Data URI format
  validateBase64Audio(base64String?: string): {isValid: boolean, details: string} {
    if (!base64String) {
      return {
        isValid: false,
        details: 'Base64 string is null or undefined'
      };
    }

    // Check if it has the correct MIME prefix (Data URI format)
    const expectedPrefix = 'data:audio/m4a;base64,';
    if (!base64String.startsWith(expectedPrefix)) {
      return {
        isValid: false,
        details: `Missing MIME prefix. Expected: "${expectedPrefix}", got: "${base64String.substring(0, 30)}..."`
      };
    }

    // Extract the actual base64 data
    const base64Data = base64String.substring(expectedPrefix.length);

    if (base64Data.length === 0) {
      return {
        isValid: false,
        details: 'No base64 data found after MIME prefix'
      };
    }

    // Basic base64 validation (should only contain valid base64 characters)
    const base64Regex = /^[A-Za-z0-9+/=]*$/;
    if (!base64Regex.test(base64Data)) {
      return {
        isValid: false,
        details: 'Invalid base64 characters found'
      };
    }

    // Check if length is reasonable (should be multiple of 4 for proper base64)
    if (base64Data.length % 4 !== 0) {
      return {
        isValid: false,
        details: `Invalid base64 length. Length: ${base64Data.length} (should be multiple of 4)`
      };
    }

    // Calculate estimated file size
    const estimatedBytes = (base64Data.length * 3) / 4;

    return {
      isValid: true,
      details: `✓ Valid Data URI format with ${base64Data.length} base64 characters (~${Math.round(estimatedBytes)} bytes)`
    };
  }

  async getAudioInfo() {
    if (!this.audioInfo()) {
      console.error('No audio available to get info from.');
      return;
    }

    try {
      const res = await CapacitorAudioEngine.getAudioInfo({
        uri: this.audioInfo()!.uri
      });
      console.log("🚀 ~ HomePage ~ getAudioInfo ~ res:", res);

      const alert = await this.alertController.create({
        header: 'Audio Information',
        message: `
          <strong>Filename:</strong> ${res.filename}<br>
          <strong>Duration:</strong> ${res.duration}s<br>
          <strong>Size:</strong> ${(res.size / 1024).toFixed(1)} KB<br>
          <strong>Sample Rate:</strong> ${res.sampleRate} Hz<br>
          <strong>Channels:</strong> ${res.channels}<br>
          <strong>Bitrate:</strong> ${res.bitrate} bps<br>
          <strong>MIME Type:</strong> ${res.mimeType}
        `,
        buttons: ['OK']
      });

      await alert.present();
    } catch (error) {
      console.error("Error getting audio info:", error);
      const alert = await this.alertController.create({
        header: 'Error',
        message: `Failed to get audio info: ${error}`,
        buttons: ['OK']
      });
      await alert.present();
    }
  }

  async testRemoteAudioInfo() {
    try {
      // Test with a remote audio URL (you can replace this with any valid audio URL)
      const remoteUrl = 'https://www.soundjay.com/misc/sounds/bell-ringing-05.wav';

      const res = await CapacitorAudioEngine.getAudioInfo({
        uri: remoteUrl
      });
      console.log("🚀 ~ HomePage ~ testRemoteAudioInfo ~ res:", res);

      const alert = await this.alertController.create({
        header: 'Remote Audio Information',
        message: `
          <strong>URL:</strong> ${remoteUrl}<br>
          <strong>Filename:</strong> ${res.filename}<br>
          <strong>Duration:</strong> ${res.duration}s<br>
          <strong>MIME Type:</strong> ${res.mimeType}<br>
          <strong>Sample Rate:</strong> ${res.sampleRate} Hz<br>
          <strong>Channels:</strong> ${res.channels}<br>
          <strong>Bitrate:</strong> ${res.bitrate} bps
        `,
        buttons: ['OK']
      });

      await alert.present();
    } catch (error) {
      console.error("Error getting remote audio info:", error);
      const alert = await this.alertController.create({
        header: 'Error',
        message: `Failed to get remote audio info: ${error}`,
        buttons: ['OK']
      });
      await alert.present();
    }
  }

  // CDN Audio Testing Methods
  async preloadCdnAudio(url: string) {
    if (!url) {
      this.cdnPreloadStatus.set('Please select a CDN URL first');
      return;
    }

    this.isPreloading.set(true);
    this.cdnPreloadStatus.set('Preloading CDN audio...');
    this.cdnAudioInfo.set(null);

    try {
      console.log('Preloading CDN audio from:', url);

      const result = await CapacitorAudioEngine.preload({
        uri: url,
        prepare: true
      });

      console.log('CDN preload result:', result);

      this.cdnPreloadStatus.set('✅ CDN audio preloaded successfully!');
      this.selectedCdnUrl.set(url);

      // Get audio info for the preloaded file
      try {
        const audioInfo = await CapacitorAudioEngine.getAudioInfo({ uri: url });
        this.cdnAudioInfo.set(audioInfo);
        console.log('CDN audio info:', audioInfo);
      } catch (infoError) {
        console.warn('Could not get audio info:', infoError);
      }

    } catch (error) {
      console.error('CDN preload error:', error);
      this.cdnPreloadStatus.set(`❌ Failed to preload: ${error}`);
    } finally {
      this.isPreloading.set(false);
    }
  }

  async playCdnAudio(url: string) {
    if (!url) {
      this.cdnPlaybackStatus.set('Please select a CDN URL first');
      return;
    }

    this.cdnPlaybackStatus.set('Starting CDN audio playback...');

    try {
      console.log('Playing CDN audio from:', url);

      await CapacitorAudioEngine.startPlayback({
        uri: url,
        speed: this.playbackSpeed(),
        volume: this.playbackVolume(),
        loop: this.isLooping()
      });

      this.cdnPlaybackStatus.set('🎵 CDN audio playing');
      this.isPlaying.set(true);

    } catch (error) {
      console.error('CDN playback error:', error);
      this.cdnPlaybackStatus.set(`❌ Failed to play: ${error}`);
    }
  }

  async stopCdnAudio() {
    try {
      await CapacitorAudioEngine.stopPlayback();
      this.cdnPlaybackStatus.set('⏹️ CDN audio stopped');
      this.isPlaying.set(false);
    } catch (error) {
      console.error('Error stopping CDN audio:', error);
      this.cdnPlaybackStatus.set(`❌ Failed to stop: ${error}`);
    }
  }

  async pauseCdnAudio() {
    try {
      await CapacitorAudioEngine.pausePlayback();
      this.cdnPlaybackStatus.set('⏸️ CDN audio paused');
      this.isPlaying.set(false);
    } catch (error) {
      console.error('Error pausing CDN audio:', error);
      this.cdnPlaybackStatus.set(`❌ Failed to pause: ${error}`);
    }
  }

  async resumeCdnAudio() {
    try {
      await CapacitorAudioEngine.resumePlayback();
      this.cdnPlaybackStatus.set('▶️ CDN audio resumed');
      this.isPlaying.set(true);
    } catch (error) {
      console.error('Error resuming CDN audio:', error);
      this.cdnPlaybackStatus.set(`❌ Failed to resume: ${error}`);
    }
  }

  selectCdnUrl(url: string) {
    this.selectedCdnUrl.set(url);
    this.cdnPreloadStatus.set('');
    this.cdnPlaybackStatus.set('');
    this.cdnAudioInfo.set(null);
  }

  formatFileSize(bytes: number): string {
    if (bytes === 0) return '0 Bytes';
    const k = 1024;
    const sizes = ['Bytes', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
  }

  formatDuration(seconds: number): string {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  }
}
