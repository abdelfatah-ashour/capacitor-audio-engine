import {
  Component,
  ElementRef,
  ViewChild,
  Input,
  ChangeDetectionStrategy,
  OnChanges,
  SimpleChanges,
  AfterViewInit,
  signal,
  computed,
  effect,
} from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-waveform-visualizer',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="waveform-container" [style.width.px]="width" [style.height.px]="height">
      <canvas
        #canvas
        [width]="width"
        [height]="height"
        [class]="cssClass"
        [style.display]="'block'"
        [style.border-radius.px]="borderRadius"
      >
      </canvas>

      @if (showLabels) {
        <div class="waveform-labels">
          <span class="label-start">{{ startLabel }}</span>
          <span class="label-end">{{ endLabel }}</span>
        </div>
      }

      @if (showStatistics) {
        <div class="waveform-stats">
          <small>
            Points: {{ levels().length }} | Max: {{ maxLevel().toFixed(3) }} | Avg:
            {{ avgLevel().toFixed(3) }}
          </small>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .waveform-container {
        position: relative;
        display: inline-block;
      }

      canvas {
        display: block;
        background: var(--waveform-bg, #f8f9fa);
        border: 1px solid var(--waveform-border, #dee2e6);
      }

      .waveform-labels {
        display: flex;
        justify-content: space-between;
        margin-top: 4px;
        font-size: 10px;
        color: var(--waveform-label-color, #6c757d);
      }

      .waveform-stats {
        margin-top: 4px;
        text-align: center;
        font-size: 10px;
        color: var(--waveform-stats-color, #6c757d);
        font-family: monospace;
      }
    `,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class WaveformVisualizerComponent implements OnChanges, AfterViewInit {
  @ViewChild('canvas', { static: true }) canvas!: ElementRef<HTMLCanvasElement>;

  // Input properties
  @Input() data: number[] = [];
  @Input() width: number = 400;
  @Input() height: number = 100;
  @Input() barWidth: number = 2;
  @Input() barGap: number = 1;
  @Input() color: string = '#3b82f6';
  @Input() backgroundColor: string = '#f8f9fa';
  @Input() peakColor: string = '#ef4444';
  @Input() showPeaks: boolean = true;
  @Input() peakThreshold: number = 0.7;
  @Input() animated: boolean = true;
  @Input() cssClass: string = '';
  @Input() borderRadius: number = 4;
  @Input() showLabels: boolean = false;
  @Input() startLabel: string = '';
  @Input() endLabel: string = '';
  @Input() showStatistics: boolean = false;

  // Internal signals for reactive updates
  protected readonly levels = signal<number[]>([]);
  private readonly previousLevels = signal<number[]>([]);
  private readonly animationProgress = signal(1);

  // Computed signals for statistics
  protected readonly maxLevel = computed(() => {
    const data = this.levels();
    return data.length > 0 ? Math.max(...data) : 0;
  });

  protected readonly avgLevel = computed(() => {
    const data = this.levels();
    return data.length > 0 ? data.reduce((sum, val) => sum + val, 0) / data.length : 0;
  });

  private animationId: number | null = null;

  constructor() {
    // Effect to redraw canvas when levels change
    effect(() => {
      const currentLevels = this.levels();
      const progress = this.animationProgress();

      if (this.canvas?.nativeElement) {
        this.drawWaveform(currentLevels, progress);
      }
    });
  }

  ngAfterViewInit(): void {
    // Initial draw
    this.drawWaveform(this.levels(), 1);
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['data'] && this.data) {
      this.updateLevels(this.data);
    }
  }

  private updateLevels(newLevels: number[]): void {
    if (this.animated) {
      this.previousLevels.set(this.levels());
      this.startAnimation();
    }
    this.levels.set([...newLevels]);
  }

  private startAnimation(): void {
    if (!this.animated) {
      this.animationProgress.set(1);
      return;
    }

    // Cancel any existing animation
    if (this.animationId) {
      cancelAnimationFrame(this.animationId);
    }

    this.animationProgress.set(0);

    const animate = () => {
      this.animationProgress.update(progress => {
        const newProgress = Math.min(1, progress + 0.1);

        if (newProgress < 1) {
          this.animationId = requestAnimationFrame(animate);
        } else {
          this.animationId = null;
        }

        return newProgress;
      });
    };

    this.animationId = requestAnimationFrame(animate);
  }

  private drawWaveform(currentLevels: number[], progress: number): void {
    const canvas = this.canvas?.nativeElement;
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    // Set canvas size
    canvas.width = this.width;
    canvas.height = this.height;

    // Clear canvas
    ctx.fillStyle = this.backgroundColor;
    ctx.fillRect(0, 0, this.width, this.height);

    if (currentLevels.length === 0) return;

    // Calculate bar dimensions
    const totalBars = Math.floor(this.width / (this.barWidth + this.barGap));
    const displayLevels = currentLevels.slice(-totalBars); // Show most recent data
    const previousLevels = this.previousLevels().slice(-totalBars);

    // Draw bars
    displayLevels.forEach((level, index) => {
      const x = index * (this.barWidth + this.barGap);
      const normalizedLevel = Math.max(0, Math.min(1, level));

      // Interpolate between previous and current level for smooth animation
      let displayLevel = normalizedLevel;
      if (this.animated && previousLevels[index] !== undefined && progress < 1) {
        displayLevel = previousLevels[index] + (normalizedLevel - previousLevels[index]) * progress;
      }

      const barHeight = displayLevel * this.height;
      const y = (this.height - barHeight) / 2;

      // Determine color (peak detection)
      const isPeak = this.showPeaks && normalizedLevel > this.peakThreshold;
      ctx.fillStyle = isPeak ? this.peakColor : this.color;

      // Draw bar with rounded corners
      this.drawRoundedRect(ctx, x, y, this.barWidth, barHeight, this.barWidth / 2);
    });
  }

  private drawRoundedRect(
    ctx: CanvasRenderingContext2D,
    x: number,
    y: number,
    width: number,
    height: number,
    radius: number
  ): void {
    ctx.beginPath();
    ctx.roundRect(x, y, width, height, radius);
    ctx.fill();
  }

  /**
   * Public method to trigger redraw
   */
  redraw(): void {
    this.drawWaveform(this.levels(), this.animationProgress());
  }

  /**
   * Public method to export canvas as image
   */
  exportAsImage(format: 'png' | 'jpeg' = 'png'): string {
    const canvas = this.canvas?.nativeElement;
    if (!canvas) return '';

    return canvas.toDataURL(`image/${format}`);
  }

  /**
   * Public method to get current waveform data
   */
  getCurrentData(): number[] {
    return [...this.levels()];
  }
}
