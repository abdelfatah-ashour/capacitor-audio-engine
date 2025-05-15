import { Component,  OnInit, signal, OnDestroy } from '@angular/core';
import { IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon, IonRange } from '@ionic/angular/standalone';
import { CapacitorAudioEngine } from "capacitor-audio-engine";
import { CommonModule } from '@angular/common';
import { addIcons } from 'ionicons';
import { playOutline, pauseOutline, stopOutline, micOutline, keyOutline, timeOutline } from 'ionicons/icons';
import { FormsModule } from '@angular/forms';
import { Capacitor } from '@capacitor/core';

@Component({
  selector: 'app-home',
  templateUrl: 'home.page.html',
  styleUrls: ['home.page.scss'],
  imports: [CommonModule, FormsModule, IonHeader, IonToolbar, IonTitle, IonContent, IonText, IonButton, IonIcon,IonRange],
  standalone: true
})
export class HomePage implements OnInit, OnDestroy{
  startAudioTime = signal<number>(0);
  endAudioTime = signal<number>(0);
  currentAudioTime = signal<number>(0);

  hasPermission = false;
  isRecording = false;
  hasRecording = false;
  isPlaying = false;
  isPaused = false;
  recordingUrl = signal<string>('');
  currentTime = 0;
  duration = 0;

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
      timeOutline
    });
  }

  ngOnInit(): void {
    this.checkPermission();

    CapacitorAudioEngine.getStatus().then((res) => {
      console.log("🚀 ~ getStatus:", res)
      this.isRecording = res.isRecording;
    })

     document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        this.checkRecordingState();
      }
    });

    // Start monitoring for interruptions
    this.setupInterruptionListener();
  }

  ngOnDestroy() {
    // Clean up listener when component is destroyed
    CapacitorAudioEngine.removeAllListeners();
  }

  private async setupInterruptionListener() {
    try {
      // First add the listener
      CapacitorAudioEngine.addListener('recordingInterruption', (data: any) => {
        console.log('Recording interruption:', data.message);
        this.handleRecordingInterruption(data.message);
      });

      // Then start monitoring
      await CapacitorAudioEngine.startMonitoring();
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

  checkPermission() {
    CapacitorAudioEngine.checkPermission().then((res) => {
      this.hasPermission = !!res;
    });
  }

  requestPermission() {
    CapacitorAudioEngine.requestPermission().then((res) => {
      this.hasPermission = !!res;
    });
  }

  // Add quality selection method
  setRecordingQuality(type: 'voice' | 'stereo') {
    const quality = this.recordingQualities[type];
    this.recordingChannels = quality.channels;
    console.log(`Setting recording quality to ${quality.label}`, quality);
  }

  async startRecording() {
    try {
      const { granted } = await CapacitorAudioEngine.requestPermission();
      if (!granted) return;

      await CapacitorAudioEngine.startRecording();
      this.isRecording = true;
      console.log('Recording started');
    } catch (error) {
      console.error('Failed to start recording:', error);
    }
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

  trimAudio() {
      CapacitorAudioEngine.trimAudio({
        path: this.recordingUrl(),
        startTime: this.startAudioTime(),
        endTime: this.endAudioTime()
      }).then(async (res) => {
        const file = await Capacitor.convertFileSrc(res.uri);
        // update time signals
        const durationInSeconds = res.duration;
        // Initialize the range with start at 0 and end at total duration
        this.duration = durationInSeconds;
        this.startAudioTime.set(0);
        this.endAudioTime.set(this.duration);
        this.currentAudioTime.set(0);
        this.recordingUrl.set(file);
      }).catch((error) => {
        console.error("Error trimming audio:", error);
      })
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
          await CapacitorAudioEngine.startRecording();
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
      await CapacitorAudioEngine.startMonitoring();
      await CapacitorAudioEngine.addListener('recordingInterruption', (data: any) => {
        console.log('Test interruption received:', data);
      });
      console.log('Test listener setup complete');
    } catch (error) {
      console.error('Test listener setup failed:', error);
    }
  }
}
