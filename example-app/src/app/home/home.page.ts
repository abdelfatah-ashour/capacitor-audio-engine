import { Component,  OnInit, signal, OnDestroy, inject } from '@angular/core';
import { IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon, IonRange, IonItem, IonLabel, IonSelect, IonSelectOption, IonCard, IonCardHeader, IonCardTitle, IonCardContent, IonBadge, IonSpinner } from '@ionic/angular/standalone';
import { CapacitorAudioEngine,AudioFileInfo, MicrophoneInfo } from "capacitor-audio-engine";
import { CommonModule } from '@angular/common';
import { addIcons } from 'ionicons';
import { playOutline, pauseOutline, stopOutline, micOutline, keyOutline, timeOutline,stopCircleOutline, headsetOutline, phonePortraitOutline, bluetoothOutline, refreshOutline, warningOutline, cutOutline, bugOutline, shieldCheckmarkOutline } from 'ionicons/icons';
import { FormsModule } from '@angular/forms';
import { Capacitor } from '@capacitor/core';
import { AlertController } from '@ionic/angular';

@Component({
  selector: 'app-home',
  templateUrl: 'home.page.html',
  styleUrls: ['home.page.scss'],
  imports: [CommonModule, FormsModule, IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon,IonRange, IonItem, IonLabel, IonSelect, IonSelectOption, IonCard, IonCardHeader, IonCardTitle, IonCardContent, IonBadge, IonSpinner],
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
      shieldCheckmarkOutline
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
      console.log("🚀 ~ HomePage ~ CapacitorAudioEngine.addListener ~ data:", data.payload.duration)
      this.currentDuration.set(data.payload.duration);
    });
  }

  ngOnDestroy() {
    // Clean up listener when component is destroyed
    CapacitorAudioEngine.removeAllListeners();
  }

  private async setupInterruptionListener() {
    try {
      // First add the listener
      CapacitorAudioEngine.addListener('recordingInterruption', (data) => {
        console.log("🚀 ~ HomePage ~ CapacitorAudioEngine.addListener ~ data:", data.payload.message)
        this.handleRecordingInterruption(data.payload.message);
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
      console.log("🚀 ~ HomePage ~ stopRecording ~ res:", res)
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
      await this.checkMicrophoneBusy();
  }

  async loadAvailableMicrophones() {
    if (Capacitor.isNativePlatform()) {
      this.isLoadingMicrophones.set(true);
      try {
        const result = await CapacitorAudioEngine.getAvailableMicrophones();
        console.log("🚀 ~ HomePage ~ loadAvailableMicrophones ~ result:", JSON.stringify(result,null,2))
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
}
