import {
  Component,
  ChangeDetectionStrategy,
  inject,
  signal,
  OnInit,
  OnDestroy,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  IonCard,
  IonCardHeader,
  IonCardTitle,
  IonCardContent,
  IonButton,
  IonIcon,
  IonSelect,
  IonSelectOption,
  IonItem,
  IonLabel,
  IonChip,
  IonGrid,
  IonRow,
  IonCol,
  IonNote,
  IonCheckbox,
  IonRange,
} from '@ionic/angular/standalone';
import {
  play,
  pause,
  stop,
  refresh,
  settings,
  analytics,
  time,
  pulse,
  checkmarkCircle,
} from 'ionicons/icons';
import { addIcons } from 'ionicons';

import { WaveformBufferService } from '../services/waveform-buffer.service';
import { WaveformVisualizerComponent } from './waveform-visualizer.component';

interface WaveformControlSettings {
  preset: 'short' | 'long' | 'high-quality' | 'custom';
  maxPoints: number;
  compressionEnabled: boolean;
  peakDetection: boolean;
  animationEnabled: boolean;
  autoZoom: boolean;
}

@Component({
  selector: 'app-intelligent-waveform',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    WaveformVisualizerComponent,
    IonCard,
    IonCardHeader,
    IonCardTitle,
    IonCardContent,
    IonButton,
    IonIcon,
    IonSelect,
    IonSelectOption,
    IonItem,
    IonLabel,
    IonChip,
    IonGrid,
    IonRow,
    IonCol,
    IonNote,
    IonCheckbox,
    IonRange,
  ],
  template: `
    <ion-card>
      <ion-card-header>
        <ion-card-title>
          <ion-icon name="analytics"></ion-icon>
          Intelligent Waveform Buffer
          @if (bufferService.isActive()) {
            <ion-chip color="success" size="small">
              <ion-icon name="pulse"></ion-icon>
              <ion-label>Recording</ion-label>
            </ion-chip>
          }
        </ion-card-title>
      </ion-card-header>

      <ion-card-content>
        <!-- Waveform Visualization -->
        <div class="waveform-section">
          <app-waveform-visualizer
            [data]="bufferService.displayData()"
            [width]="waveformSettings().visualizationWidth"
            [height]="waveformSettings().visualizationHeight"
            [color]="waveformSettings().primaryColor"
            [peakColor]="waveformSettings().peakColor"
            [backgroundColor]="waveformSettings().backgroundColor"
            [animated]="settings().animationEnabled"
            [showPeaks]="settings().peakDetection"
            [showStatistics]="true"
            [showLabels]="true"
            [startLabel]="getStartLabel()"
            [endLabel]="getEndLabel()"
          >
          </app-waveform-visualizer>
        </div>

        <!-- Control Panel -->
        <ion-grid class="controls-grid">
          <ion-row>
            <!-- Recording Controls -->
            <ion-col size="12" size-md="4">
              <div class="control-section">
                <h4>Recording Controls</h4>
                <ion-button
                  expand="block"
                  fill="solid"
                  [color]="bufferService.isActive() ? 'danger' : 'success'"
                  (click)="toggleRecording()"
                >
                  <ion-icon [name]="bufferService.isActive() ? 'stop' : 'play'"></ion-icon>
                  {{ bufferService.isActive() ? 'Stop Recording' : 'Start Recording' }}
                </ion-button>

                <ion-button expand="block" fill="outline" color="medium" (click)="resetBuffer()">
                  <ion-icon name="refresh"></ion-icon>
                  Reset Buffer
                </ion-button>
              </div>
            </ion-col>

            <!-- Zoom Controls -->
            <ion-col size="12" size-md="4">
              <div class="control-section">
                <h4>Zoom Level</h4>
                <ion-select
                  [value]="bufferService.currentZoom()"
                  (ionChange)="onZoomChange($event)"
                  interface="popover"
                  placeholder="Select zoom level"
                >
                  @for (zoom of bufferService.availableZooms(); track zoom.name) {
                    <ion-select-option [value]="zoom.name">
                      {{ zoom.description }}
                    </ion-select-option>
                  }
                </ion-select>

                <div class="zoom-buttons">
                  @for (zoom of quickZoomLevels; track zoom.name) {
                    <ion-button
                      size="small"
                      fill="outline"
                      [color]="bufferService.currentZoom() === zoom.name ? 'primary' : 'medium'"
                      (click)="setZoom(zoom.name)"
                    >
                      {{ zoom.label }}
                    </ion-button>
                  }
                </div>
              </div>
            </ion-col>

            <!-- Settings -->
            <ion-col size="12" size-md="4">
              <div class="control-section">
                <h4>Settings</h4>

                <ion-item lines="none">
                  <ion-checkbox
                    [(ngModel)]="settings().compressionEnabled"
                    (ngModelChange)="updateSettings()"
                  >
                  </ion-checkbox>
                  <ion-label class="ion-margin-start">Smart Compression</ion-label>
                </ion-item>

                <ion-item lines="none">
                  <ion-checkbox
                    [(ngModel)]="settings().peakDetection"
                    (ngModelChange)="updateSettings()"
                  >
                  </ion-checkbox>
                  <ion-label class="ion-margin-start">Peak Detection</ion-label>
                </ion-item>

                <ion-item lines="none">
                  <ion-checkbox
                    [(ngModel)]="settings().animationEnabled"
                    (ngModelChange)="updateSettings()"
                  >
                  </ion-checkbox>
                  <ion-label class="ion-margin-start">Smooth Animation</ion-label>
                </ion-item>

                <ion-item lines="none">
                  <ion-label>Max Display Points</ion-label>
                  <ion-range
                    min="100"
                    max="800"
                    step="50"
                    [(ngModel)]="settings().maxPoints"
                    (ngModelChange)="updateSettings()"
                    color="primary"
                  >
                    <ion-label slot="start">100</ion-label>
                    <ion-label slot="end">800</ion-label>
                  </ion-range>
                </ion-item>
              </div>
            </ion-col>
          </ion-row>
        </ion-grid>

        <!-- Statistics Display -->
        <div class="statistics-section">
          <h4>
            <ion-icon name="analytics"></ion-icon>
            Real-time Statistics
          </h4>

          <ion-grid>
            <ion-row>
              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">
                    {{ formatDuration(bufferService.recordingDuration()) }}
                  </div>
                  <div class="stat-label">Duration</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">
                    {{ bufferService.statistics().totalPoints.toLocaleString() }}
                  </div>
                  <div class="stat-label">Data Points</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">{{ bufferService.compressionRatio().toFixed(1) }}x</div>
                  <div class="stat-label">Compression</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">{{ bufferService.memoryEfficiency().toFixed(1) }}%</div>
                  <div class="stat-label">Memory Saved</div>
                </div>
              </ion-col>
            </ion-row>

            <ion-row>
              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">
                    {{ bufferService.statistics().bufferSizeKB.toFixed(1) }} KB
                  </div>
                  <div class="stat-label">Memory Usage</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">{{ bufferService.statistics().peakCount }}</div>
                  <div class="stat-label">Peaks Detected</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">{{ bufferService.displayData().length }}</div>
                  <div class="stat-label">Display Points</div>
                </div>
              </ion-col>

              <ion-col size="6" size-md="3">
                <div class="stat-card">
                  <div class="stat-value">{{ getCompressionRatio() }}%</div>
                  <div class="stat-label">UI Efficiency</div>
                </div>
              </ion-col>
            </ion-row>
          </ion-grid>
        </div>

        <!-- Performance Indicator -->
        @if (showPerformanceIndicator()) {
          <ion-note color="success">
            <ion-icon name="checkmark-circle"></ion-icon>
            Optimal performance: {{ bufferService.displayData().length }} points displayed
            efficiently
          </ion-note>
        }
      </ion-card-content>
    </ion-card>
  `,
  styles: [
    `
      .waveform-section {
        margin-bottom: 20px;
        text-align: center;
      }

      .controls-grid {
        margin: 20px 0;
      }

      .control-section {
        padding: 16px;
        border: 1px solid var(--ion-color-light);
        border-radius: 8px;
        margin-bottom: 16px;
      }

      .control-section h4 {
        margin: 0 0 12px 0;
        font-size: 14px;
        font-weight: 600;
        color: var(--ion-color-dark);
      }

      .zoom-buttons {
        display: flex;
        gap: 8px;
        margin-top: 12px;
        flex-wrap: wrap;
      }

      .statistics-section {
        margin-top: 24px;
        padding: 16px;
        background: var(--ion-color-light);
        border-radius: 8px;
      }

      .statistics-section h4 {
        margin: 0 0 16px 0;
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 16px;
        font-weight: 600;
      }

      .stat-card {
        text-align: center;
        padding: 12px;
        background: white;
        border-radius: 8px;
        margin-bottom: 8px;
      }

      .stat-value {
        font-size: 18px;
        font-weight: bold;
        color: var(--ion-color-primary);
        margin-bottom: 4px;
      }

      .stat-label {
        font-size: 12px;
        color: var(--ion-color-medium);
        text-transform: uppercase;
        letter-spacing: 0.5px;
      }

      ion-note {
        display: flex;
        align-items: center;
        gap: 8px;
        margin-top: 16px;
        padding: 12px;
        border-radius: 8px;
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntelligentWaveformComponent implements OnInit, OnDestroy {
  protected readonly bufferService = inject(WaveformBufferService);

  // Component settings
  protected readonly settings = signal<WaveformControlSettings>({
    preset: 'long',
    maxPoints: 400,
    compressionEnabled: true,
    peakDetection: true,
    animationEnabled: true,
    autoZoom: false,
  });

  // Waveform visualization settings
  protected readonly waveformSettings = signal({
    visualizationWidth: 400,
    visualizationHeight: 120,
    primaryColor: '#3b82f6',
    peakColor: '#ef4444',
    backgroundColor: '#f8f9fa',
  });

  // Quick zoom level buttons
  protected readonly quickZoomLevels = [
    { name: 'recent', label: '1m' },
    { name: 'short', label: '5m' },
    { name: 'medium', label: '20m' },
    { name: 'full', label: 'All' },
  ];

  // Simulation timer for demo
  private simulationTimer: any = null;

  constructor() {
    // Register Ionic icons
    addIcons({
      play,
      pause,
      stop,
      refresh,
      settings,
      analytics,
      time,
      pulse,
      checkmarkCircle,
    });
  }

  ngOnInit(): void {
    // Initialize with optimal settings for long recordings
    this.bufferService.updateConfig({
      maxTotalPoints: this.settings().maxPoints,
      recentDataMinutes: 5,
      recentDataPoints: 150,
      resolutionLevels: [
        { timeRangeMinutes: 5, maxPoints: 150, compressionMethod: 'rms' },
        { timeRangeMinutes: 20, maxPoints: 150, compressionMethod: 'peak' },
        { timeRangeMinutes: 60, maxPoints: 120, compressionMethod: 'rms' },
        { timeRangeMinutes: 120, maxPoints: 80, compressionMethod: 'max' },
      ],
    });
  }

  ngOnDestroy(): void {
    if (this.simulationTimer) {
      clearInterval(this.simulationTimer);
    }
  }

  protected toggleRecording(): void {
    if (this.bufferService.isActive()) {
      this.stopRecording();
    } else {
      this.startRecording();
    }
  }

  private startRecording(): void {
    this.bufferService.startRecording();

    // Start simulation (in real app, this would come from native audio events)
    this.simulationTimer = setInterval(() => {
      // Generate realistic audio levels with some peaks and silence
      let level = Math.random() * 0.4 + 0.1; // Base level 0.1-0.5

      // Add speech-like peaks (15% chance)
      if (Math.random() < 0.15) {
        level = Math.random() * 0.5 + 0.5; // Peak level 0.5-1.0
      }

      // Add silence periods (5% chance)
      if (Math.random() < 0.05) {
        level = Math.random() * 0.05; // Very low level
      }

      this.bufferService.addLevel(level);
    }, 50); // 50ms intervals to match native audio
  }

  private stopRecording(): void {
    this.bufferService.stopRecording();

    if (this.simulationTimer) {
      clearInterval(this.simulationTimer);
      this.simulationTimer = null;
    }
  }

  protected resetBuffer(): void {
    this.stopRecording();
    this.bufferService.reset();
  }

  protected setZoom(zoomLevel: string): void {
    this.bufferService.setZoom(zoomLevel);
  }

  protected onZoomChange(event: any): void {
    this.bufferService.setZoom(event.detail.value);
  }

  protected updateSettings(): void {
    const currentSettings = this.settings();

    // Update buffer configuration based on settings
    this.bufferService.updateConfig({
      maxTotalPoints: currentSettings.maxPoints,
    });
  }

  protected formatDuration(minutes: number): string {
    if (minutes < 1) {
      return `${Math.round(minutes * 60)}s`;
    }
    return `${minutes.toFixed(1)}m`;
  }

  protected getStartLabel(): string {
    const stats = this.bufferService.statistics();
    const zoomConfig = this.bufferService
      .availableZooms()
      .find(z => z.name === this.bufferService.currentZoom());

    if (!zoomConfig) return '';

    const startMinutes = Math.max(0, stats.recordingDurationMinutes - zoomConfig.durationMinutes);
    return `-${this.formatDuration(zoomConfig.durationMinutes)}`;
  }

  protected getEndLabel(): string {
    return 'Now';
  }

  protected getCompressionRatio(): number {
    const stats = this.bufferService.statistics();
    const displayPoints = this.bufferService.displayData().length;

    if (stats.recordingDurationMinutes === 0) return 0;

    const expectedPointsForTimeframe = this.getExpectedPointsForCurrentZoom();
    return expectedPointsForTimeframe > 0
      ? Math.round((1 - displayPoints / expectedPointsForTimeframe) * 100)
      : 0;
  }

  private getExpectedPointsForCurrentZoom(): number {
    const zoomConfig = this.bufferService
      .availableZooms()
      .find(z => z.name === this.bufferService.currentZoom());
    if (!zoomConfig) return 0;

    const stats = this.bufferService.statistics();
    const actualDuration = Math.min(stats.recordingDurationMinutes, zoomConfig.durationMinutes);
    return Math.floor((actualDuration * 60 * 1000) / 50); // 50ms intervals
  }

  protected showPerformanceIndicator(): boolean {
    const displayPoints = this.bufferService.displayData().length;
    return displayPoints > 0 && displayPoints <= 500; // Good performance range
  }
}
