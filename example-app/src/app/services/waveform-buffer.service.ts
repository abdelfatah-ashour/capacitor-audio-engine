import { Injectable, computed, signal } from '@angular/core';

/**
 * Angular service for intelligent waveform data management
 * Optimized for long recordings (60+ minutes) with efficient memory usage
 */

export interface WaveformPoint {
  level: number;
  timestamp: number;
  isPeak?: boolean;
}

export interface WaveformResolution {
  timeRangeMinutes: number;
  maxPoints: number;
  compressionMethod: 'rms' | 'peak' | 'average' | 'max';
}

export interface ZoomLevel {
  name: string;
  durationMinutes: number;
  maxPoints: number;
  description: string;
}

export interface WaveformBufferConfig {
  maxTotalPoints: number;
  recentDataMinutes: number;
  recentDataPoints: number;
  peakThreshold: number;
  resolutionLevels: WaveformResolution[];
  zoomLevels: ZoomLevel[];
}

export interface WaveformStatistics {
  totalPoints: number;
  recordingDurationMinutes: number;
  bufferSizeKB: number;
  compressionRatio: number;
  peakCount: number;
  memoryEfficiency: number;
}

@Injectable({
  providedIn: 'root',
})
export class WaveformBufferService {
  private buffer: WaveformPoint[] = [];
  private recordingStartTime: number = 0;
  private frameCount: number = 0;

  // Peak detection state
  private peakDetectionWindow: number = 5;
  private lastPeakTime: number = 0;
  private minPeakDistance: number = 100;

  // Angular signals for reactive state
  private readonly _isActive = signal(false);
  private readonly _statistics = signal<WaveformStatistics>({
    totalPoints: 0,
    recordingDurationMinutes: 0,
    bufferSizeKB: 0,
    compressionRatio: 1,
    peakCount: 0,
    memoryEfficiency: 0,
  });
  private readonly _currentZoom = signal<string>('medium');
  private readonly _displayData = signal<number[]>([]);

  // Default configuration optimized for long recordings
  private config: WaveformBufferConfig = {
    maxTotalPoints: 400,
    recentDataMinutes: 5,
    recentDataPoints: 150,
    peakThreshold: 0.15,
    resolutionLevels: [
      { timeRangeMinutes: 5, maxPoints: 150, compressionMethod: 'rms' },
      { timeRangeMinutes: 20, maxPoints: 150, compressionMethod: 'peak' },
      { timeRangeMinutes: 60, maxPoints: 120, compressionMethod: 'rms' },
      { timeRangeMinutes: 120, maxPoints: 80, compressionMethod: 'max' },
    ],
    zoomLevels: [
      { name: 'recent', durationMinutes: 1, maxPoints: 60, description: 'Last 1 minute' },
      { name: 'short', durationMinutes: 5, maxPoints: 200, description: 'Last 5 minutes' },
      { name: 'medium', durationMinutes: 20, maxPoints: 300, description: 'Last 20 minutes' },
      { name: 'full', durationMinutes: 120, maxPoints: 400, description: 'Full recording' },
    ],
  };

  // Public readonly signals
  public readonly isActive = this._isActive.asReadonly();
  public readonly statistics = this._statistics.asReadonly();
  public readonly currentZoom = this._currentZoom.asReadonly();
  public readonly displayData = this._displayData.asReadonly();
  public readonly availableZooms = signal(this.config.zoomLevels);

  // Computed signals
  public readonly recordingDuration = computed(() => this.statistics().recordingDurationMinutes);

  public readonly compressionRatio = computed(() => this.statistics().compressionRatio);

  public readonly memoryEfficiency = computed(() => this.statistics().memoryEfficiency);

  /**
   * Initialize the buffer for a new recording session
   */
  startRecording(): void {
    this.reset();
    this.recordingStartTime = Date.now();
    this._isActive.set(true);
    console.log('[WaveformBufferService] Recording started');
  }

  /**
   * Stop the current recording session
   */
  stopRecording(): void {
    this._isActive.set(false);
    this.updateStatistics();
    console.log('[WaveformBufferService] Recording stopped');
  }

  /**
   * Add a new waveform level to the buffer
   */
  addLevel(level: number): void {
    if (!this._isActive()) return;

    this.frameCount++;
    const timestamp = Date.now();
    const point: WaveformPoint = { level, timestamp };

    // Add peak detection
    this.detectAndMarkPeak(point);

    // Add to buffer
    this.buffer.push(point);

    // Manage buffer size and apply compression
    this.manageBufferSize();

    // Update display data for current zoom
    this.updateDisplayData();

    // Update statistics periodically
    if (this.frameCount % 100 === 0) {
      this.updateStatistics();
    }
  }

  /**
   * Set zoom level and update display data
   */
  setZoom(zoomLevel: string | number): void {
    this._currentZoom.set(typeof zoomLevel === 'string' ? zoomLevel : 'custom');
    this.updateDisplayData(zoomLevel);
  }

  /**
   * Get display data for specific zoom level
   */
  getDisplayData(zoomLevel?: string | number, maxPoints?: number): number[] {
    const targetConfig = this.getZoomConfig(zoomLevel || this._currentZoom(), maxPoints);
    const timeWindowMs = targetConfig.durationMinutes * 60 * 1000;
    const cutoffTime = Date.now() - timeWindowMs;

    // Filter data within time window
    const relevantData = this.buffer.filter(point => point.timestamp >= cutoffTime);

    if (relevantData.length === 0) {
      return [];
    }

    // If data fits within target points, return as-is
    if (relevantData.length <= targetConfig.maxPoints) {
      return relevantData.map(point => point.level);
    }

    // Apply intelligent downsampling
    return this.downsampleData(relevantData, targetConfig.maxPoints);
  }

  /**
   * Update configuration
   */
  updateConfig(newConfig: Partial<WaveformBufferConfig>): void {
    this.config = { ...this.config, ...newConfig };
    this.availableZooms.set(this.config.zoomLevels);
    console.log('[WaveformBufferService] Configuration updated');
  }

  /**
   * Reset buffer state
   */
  reset(): void {
    this.buffer = [];
    this.frameCount = 0;
    this.recordingStartTime = Date.now();
    this.lastPeakTime = 0;
    this._displayData.set([]);
    this._statistics.set({
      totalPoints: 0,
      recordingDurationMinutes: 0,
      bufferSizeKB: 0,
      compressionRatio: 1,
      peakCount: 0,
      memoryEfficiency: 0,
    });
    console.log('[WaveformBufferService] Buffer reset');
  }

  /**
   * Get current buffer statistics
   */
  getCurrentStatistics(): WaveformStatistics {
    return this.statistics();
  }

  // PRIVATE METHODS

  private detectAndMarkPeak(point: WaveformPoint): void {
    const { level, timestamp } = point;

    // Check if level exceeds peak threshold
    if (level < this.config.peakThreshold) {
      return;
    }

    // Ensure minimum distance between peaks
    if (timestamp - this.lastPeakTime < this.minPeakDistance) {
      return;
    }

    // Check if it's a local maximum
    const recentPoints = this.buffer.slice(-this.peakDetectionWindow);
    const isLocalPeak = recentPoints.every(p => level >= p.level);

    if (isLocalPeak) {
      point.isPeak = true;
      this.lastPeakTime = timestamp;
    }
  }

  private manageBufferSize(): void {
    if (this.buffer.length <= this.config.maxTotalPoints) {
      return;
    }

    // Apply progressive compression
    this.applyProgressiveCompression();

    // If still too large, perform final trim
    if (this.buffer.length > this.config.maxTotalPoints) {
      this.buffer = this.buffer.slice(-this.config.maxTotalPoints);
    }
  }

  private applyProgressiveCompression(): void {
    const now = Date.now();
    const recentCutoff = now - this.config.recentDataMinutes * 60 * 1000;

    // Separate recent and older data
    const recentData = this.buffer.filter(point => point.timestamp >= recentCutoff);
    const olderData = this.buffer.filter(point => point.timestamp < recentCutoff);

    if (olderData.length === 0) {
      return;
    }

    // Apply compression to older data
    const compressedOlderData = this.compressDataByTimeRanges(olderData, now);

    // Ensure recent data doesn't exceed allocation
    const trimmedRecentData =
      recentData.length > this.config.recentDataPoints
        ? recentData.slice(-this.config.recentDataPoints)
        : recentData;

    // Combine compressed older data with recent data
    this.buffer = [...compressedOlderData, ...trimmedRecentData];
  }

  private compressDataByTimeRanges(data: WaveformPoint[], now: number): WaveformPoint[] {
    const compressed: WaveformPoint[] = [];

    for (const resolution of this.config.resolutionLevels) {
      const rangeStartTime = now - resolution.timeRangeMinutes * 60 * 1000;
      const rangeEndTime =
        resolution === this.config.resolutionLevels[0]
          ? now
          : now -
            (this.config.resolutionLevels[this.config.resolutionLevels.indexOf(resolution) - 1]
              ?.timeRangeMinutes || 0) *
              60 *
              1000;

      const rangeData = data.filter(
        point => point.timestamp >= rangeStartTime && point.timestamp < rangeEndTime
      );

      if (rangeData.length === 0) continue;

      const compressedRange = this.compressDataRange(rangeData, resolution);
      compressed.push(...compressedRange);
    }

    return compressed.sort((a, b) => a.timestamp - b.timestamp);
  }

  private compressDataRange(
    data: WaveformPoint[],
    resolution: WaveformResolution
  ): WaveformPoint[] {
    if (data.length <= resolution.maxPoints) {
      return data;
    }

    const chunkSize = Math.ceil(data.length / resolution.maxPoints);
    const compressed: WaveformPoint[] = [];

    for (let i = 0; i < data.length; i += chunkSize) {
      const chunk = data.slice(i, i + chunkSize);
      const compressedPoint = this.compressChunk(chunk, resolution.compressionMethod);
      compressed.push(compressedPoint);
    }

    return compressed;
  }

  private compressChunk(chunk: WaveformPoint[], method: string): WaveformPoint {
    if (chunk.length === 0) {
      return { level: 0, timestamp: Date.now() };
    }

    if (chunk.length === 1) {
      return chunk[0];
    }

    const middleIndex = Math.floor(chunk.length / 2);
    const timestamp = chunk[middleIndex].timestamp;

    let level: number;
    let isPeak = false;

    switch (method) {
      case 'rms':
        const sumSquares = chunk.reduce((sum, point) => sum + point.level * point.level, 0);
        level = Math.sqrt(sumSquares / chunk.length);
        break;

      case 'peak':
      case 'max':
        level = Math.max(...chunk.map(point => point.level));
        isPeak = chunk.some(point => point.isPeak);
        break;

      case 'average':
      default:
        level = chunk.reduce((sum, point) => sum + point.level, 0) / chunk.length;
        break;
    }

    return { level, timestamp, isPeak };
  }

  private downsampleData(data: WaveformPoint[], targetSize: number): number[] {
    if (data.length <= targetSize) {
      return data.map(point => point.level);
    }

    const chunkSize = data.length / targetSize;
    const result: number[] = [];

    for (let i = 0; i < targetSize; i++) {
      const start = Math.floor(i * chunkSize);
      const end = Math.floor((i + 1) * chunkSize);
      const chunk = data.slice(start, end);

      if (chunk.length === 0) continue;

      // Use RMS for better audio representation
      const rms = Math.sqrt(
        chunk.reduce((sum, point) => sum + point.level * point.level, 0) / chunk.length
      );

      result.push(rms);
    }

    return result;
  }

  private updateDisplayData(zoomLevel?: string | number): void {
    const displayData = this.getDisplayData(zoomLevel);
    this._displayData.set(displayData);
  }

  private updateStatistics(): void {
    const durationMs = Date.now() - this.recordingStartTime;
    const durationMinutes = durationMs / (60 * 1000);
    const expectedPoints = Math.floor(durationMs / 50); // At 50ms intervals
    const compressionRatio = expectedPoints > 0 ? expectedPoints / this.buffer.length : 1;
    const peakCount = this.buffer.filter(p => p.isPeak).length;

    // Estimate memory usage
    const bytesPerPoint = 16; // timestamp(8) + level(4) + isPeak(1) + overhead(3)
    const bufferSizeKB = (this.buffer.length * bytesPerPoint) / 1024;

    const memoryEfficiency =
      expectedPoints > 0 ? (1 - this.buffer.length / expectedPoints) * 100 : 0;

    this._statistics.set({
      totalPoints: this.buffer.length,
      recordingDurationMinutes: durationMinutes,
      bufferSizeKB: Math.round(bufferSizeKB * 100) / 100,
      compressionRatio: Math.round(compressionRatio * 100) / 100,
      peakCount,
      memoryEfficiency: Math.round(memoryEfficiency * 100) / 100,
    });
  }

  private getZoomConfig(
    zoomLevel: string | number,
    maxPoints?: number
  ): { durationMinutes: number; maxPoints: number } {
    if (typeof zoomLevel === 'number') {
      return {
        durationMinutes: zoomLevel,
        maxPoints: maxPoints || this.config.maxTotalPoints,
      };
    }

    const zoomConfig = this.config.zoomLevels.find(level => level.name === zoomLevel);
    if (!zoomConfig) {
      console.warn(`[WaveformBufferService] Unknown zoom level: ${zoomLevel}, using 'medium'`);
      return {
        durationMinutes: 20,
        maxPoints: maxPoints || 300,
      };
    }

    return {
      durationMinutes: zoomConfig.durationMinutes,
      maxPoints: maxPoints || zoomConfig.maxPoints,
    };
  }
}
