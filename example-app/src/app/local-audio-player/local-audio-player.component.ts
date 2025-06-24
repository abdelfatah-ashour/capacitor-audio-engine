import { Component, OnInit, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IonicModule } from '@ionic/angular';
import { CapacitorAudioEngine } from 'capacitor-audio-engine';
import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { PluginListenerHandle } from '@capacitor/core';
import { AudioDownloadService } from '../services/audio-download.service';


 const urlAudio = "https://cdn.pixabay.com/audio/2025/03/19/audio_91b4c0a3b6.mp3";

const urlAudio2 = "https://cdn.pixabay.com/audio/2025/02/18/audio_67a824edf7.mp3";

const urlAudio3 = "https://cdn.pixabay.com/audio/2024/11/29/audio_45bbd49c34.mp3";

const urlAudio4 = "https://cdn.pixabay.com/audio/2024/11/05/audio_da986d1e2a.mp3";


interface LocalAudioFile {
  name: string;
  fileName: string;
  localPath?: string;
  webPath?: string;
  uri?: string;
  downloaded: boolean;
  downloading: boolean;
  preloaded: boolean;
  loading: boolean;
  size?: number;
  duration?: number;
  originalUrl: string;
}

@Component({
  selector: 'app-local-audio-player',
  standalone: true,
  imports: [CommonModule, FormsModule, IonicModule],
  templateUrl: './local-audio-player.component.html',
  styleUrls: ['./local-audio-player.component.scss']
})
export class LocalAudioPlayerComponent implements OnInit, OnDestroy {

  // Sample audio files to download and store locally
  audioFiles: LocalAudioFile[] = [
    {
      name: 'Sample Audio 1',
      fileName: 'sample-audio-1.wav',
      originalUrl: urlAudio,
      downloaded: false,
      downloading: false,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 2',
      fileName: 'sample-audio-2.mp3',
      originalUrl: urlAudio2,
      downloaded: false,
      downloading: false,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 3',
      fileName: 'sample-audio-3.mp3',
      originalUrl: urlAudio3,
      downloaded: false,
      downloading: false,
      preloaded: false,
      loading: false
    },
    {
      name: 'Sample Audio 4',
      fileName: 'sample-audio-4.mp3',
      originalUrl: urlAudio4,
      downloaded: false,
      downloading: false,
      preloaded: false,
      loading: false
    }
  ];

  currentlyPlaying: string | null = null;
  playbackStatus: any = {
    status: 'idle',
    currentTime: 0,
    duration: 0,
    position: 0
  };

  // Playback controls
  playbackSpeed = 1.0;
  volume = 1.0;
  isLooping = false;

  private listeners: PluginListenerHandle[] = [];
  private readonly AUDIO_DIRECTORY = 'audio_files';

  constructor(private audioDownloadService: AudioDownloadService) { }

  ngOnInit() {
    this.setupPlaybackListeners();
    this.checkExistingFiles();
  }

  // Template helper methods
  get allDownloaded(): boolean {
    return this.audioFiles.every(audio => audio.downloaded);
  }

  get allPreloaded(): boolean {
    return this.audioFiles.every(audio => audio.preloaded);
  }

  get anyPlaying(): boolean {
    return this.currentlyPlaying !== null;
  }

  get anyDownloading(): boolean {
    return this.audioFiles.some(audio => audio.downloading);
  }

  get anyLoading(): boolean {
    return this.audioFiles.some(audio => audio.loading);
  }

  get downloadAllDisabled(): boolean {
    return this.anyDownloading || this.allDownloaded;
  }

  get preloadAllDisabled(): boolean {
    return this.anyLoading || this.allPreloaded || !this.allDownloaded;
  }

  get stopAllDisabled(): boolean {
    return !this.anyPlaying;
  }

  get downloadAndPreloadAllDisabled(): boolean {
    return this.anyDownloading || this.anyLoading || (this.allDownloaded && this.allPreloaded);
  }

  getFileStatus(audioFile: LocalAudioFile): string {
    if (audioFile.downloading) return 'Downloading...';
    if (audioFile.downloaded && audioFile.loading) return 'Preloading...';
    if (audioFile.downloaded && audioFile.preloaded) return 'Ready to play';
    if (audioFile.downloaded) return 'Downloaded';
    return 'Not downloaded';
  }

  getFileStatusColor(audioFile: LocalAudioFile): string {
    if (audioFile.downloading || audioFile.loading) return 'warning';
    if (audioFile.downloaded && audioFile.preloaded) return 'success';
    if (audioFile.downloaded) return 'primary';
    return 'medium';
  }

  getProgressPercentage(): number {
    const totalFiles = this.audioFiles.length;
    const downloadedFiles = this.downloadedCount;
    const preloadedFiles = this.preloadedCount;

    // Weight: download = 70%, preload = 30%
    const downloadProgress = (downloadedFiles / totalFiles) * 70;
    const preloadProgress = (preloadedFiles / totalFiles) * 30;

    return Math.round(downloadProgress + preloadProgress);
  }

  getOverallStatus(): string {
    if (this.anyDownloading || this.anyLoading) {
      const downloadingCount = this.audioFiles.filter(f => f.downloading).length;
      const loadingCount = this.audioFiles.filter(f => f.loading).length;

      if (downloadingCount > 0 && loadingCount > 0) {
        return `Downloading ${downloadingCount}, Preloading ${loadingCount}`;
      } else if (downloadingCount > 0) {
        return `Downloading ${downloadingCount} files...`;
      } else {
        return `Preloading ${loadingCount} files...`;
      }
    }

    if (this.allDownloaded && this.allPreloaded) {
      return 'All files ready for playback';
    } else if (this.allDownloaded) {
      return 'All files downloaded';
    } else if (this.downloadedCount > 0) {
      return `${this.downloadedCount}/${this.totalFiles} files downloaded`;
    }

    return 'No files downloaded';
  }

  isCurrentlyPlaying(audioFile: LocalAudioFile): boolean {
    return this.currentlyPlaying === audioFile.uri;
  }

  canStop(audioFile: LocalAudioFile): boolean {
    return this.isPlaying(audioFile) || this.isPaused(audioFile);
  }

  get downloadedCount(): number {
    return this.audioFiles.filter(f => f.downloaded).length;
  }

  get preloadedCount(): number {
    return this.audioFiles.filter(f => f.preloaded).length;
  }

  get totalFiles(): number {
    return this.audioFiles.length;
  }

  ngOnDestroy() {
    this.removeAllListeners();
    this.stopAllPlayback();
  }

  private async setupPlaybackListeners() {
    try {
      // Listen for playback status changes
      const statusListener = await CapacitorAudioEngine.addListener('playbackStatusChange', (event) => {
        console.log('Playback status changed:', event);
        this.playbackStatus = { ...this.playbackStatus, ...event };
      });

      // Listen for playback progress
      const progressListener = await CapacitorAudioEngine.addListener('playbackProgress', (event) => {
        console.log('Playback progress:', event);
        this.playbackStatus = { ...this.playbackStatus, ...event };
      });

      // Listen for playback completion
      const completedListener = await CapacitorAudioEngine.addListener('playbackCompleted', (event) => {
        console.log('Playback completed:', event);
        this.currentlyPlaying = null;
        this.playbackStatus.status = 'completed';

        // If looping is disabled, show completion message
        if (!this.isLooping) {
          const currentAudio = this.audioFiles.find(audio => audio.uri === this.currentlyPlaying);
          if (currentAudio) {
            this.showToast(`Finished playing ${currentAudio.name}`, 'success');
          }
        }
      });

      // Listen for playback errors with enhanced error handling
      const errorListener = await CapacitorAudioEngine.addListener('playbackError', (event) => {
        console.error('Playback error:', event);
        this.currentlyPlaying = null;
        this.playbackStatus.status = 'error';

        // Enhanced error message based on error type
        let errorMessage = 'Playback error';
        if (event.message) {
          if (event.message.includes('network') || event.message.includes('CORS')) {
            errorMessage = 'Network error - file may need to be re-downloaded';
          } else if (event.message.includes('format') || event.message.includes('codec')) {
            errorMessage = 'Audio format not supported';
          } else if (event.message.includes('permission')) {
            errorMessage = 'Permission denied - check audio permissions';
          } else {
            errorMessage = `Playback error: ${event.message}`;
          }
        }

        this.showToast(errorMessage, 'danger');
      });

      this.listeners = [statusListener, progressListener, completedListener, errorListener];
      console.log('Playback listeners setup successfully');
    } catch (error) {
      console.error('Failed to setup playback listeners:', error);
      this.showToast('Failed to setup audio listeners - some features may not work properly', 'warning');
    }
  }

  private async checkExistingFiles() {
    try {
      // Check if audio directory exists, create if not
      try {
        await Filesystem.readdir({
          path: this.AUDIO_DIRECTORY,
          directory: Directory.Data
        });
      } catch (error) {
        // Directory doesn't exist, create it
        await Filesystem.mkdir({
          path: this.AUDIO_DIRECTORY,
          directory: Directory.Data,
          recursive: true
        });
      }

      // Check which files already exist
      for (const audioFile of this.audioFiles) {
        try {
          const stat = await Filesystem.stat({
            path: `${this.AUDIO_DIRECTORY}/${audioFile.fileName}`,
            directory: Directory.Data
          });

          audioFile.downloaded = true;
          audioFile.size = stat.size;
          audioFile.uri = stat.uri;

          // Try to get audio info
          try {
            const audioInfo = await CapacitorAudioEngine.getAudioInfo({ uri: stat.uri });
            audioFile.duration = audioInfo.duration;
          } catch (infoError) {
            console.log('Could not get audio info for', audioFile.fileName, infoError);
          }
        } catch (error) {
          // File doesn't exist yet
          audioFile.downloaded = false;
        }
      }
    } catch (error) {
      console.error('Failed to check existing files:', error);
      this.showToast('Failed to check existing files', 'danger');
    }
  }

  async downloadAudioFile(audioFile: LocalAudioFile, autoPreload: boolean = false) {
    if (audioFile.downloaded || audioFile.downloading) return;

    audioFile.downloading = true;

    try {
      console.log(`Starting download: ${audioFile.name}`);

      // Use the enhanced download service
      const blob = await this.audioDownloadService.downloadWithRetry({
        url: audioFile.originalUrl,
        maxRetries: 3,
        retryDelay: 1000,
        timeout: 30000
      });

      // Convert to base64
      const base64Data = await this.audioDownloadService.blobToBase64(blob);
      const base64 = base64Data.split(',')[1];

      // Write to filesystem
      await Filesystem.writeFile({
        path: `${this.AUDIO_DIRECTORY}/${audioFile.fileName}`,
        data: base64,
        directory: Directory.Data
      });

      // Get file info
      const stat = await Filesystem.stat({
        path: `${this.AUDIO_DIRECTORY}/${audioFile.fileName}`,
        directory: Directory.Data
      });

      audioFile.downloaded = true;
      audioFile.downloading = false;
      audioFile.size = stat.size;
      audioFile.uri = stat.uri;
      audioFile.localPath = `${this.AUDIO_DIRECTORY}/${audioFile.fileName}`;

      // Try to get audio info
      try {
        const audioInfo = await CapacitorAudioEngine.getAudioInfo({ uri: stat.uri });
        audioFile.duration = audioInfo.duration;
      } catch (infoError) {
        console.log('Could not get audio info for', audioFile.fileName, infoError);
      }

      this.showToast(`${audioFile.name} downloaded successfully`, 'success');

      // Auto-preload if requested
      if (autoPreload) {
        await this.preloadAudio(audioFile);
      }

    } catch (error) {
      console.error('Failed to download audio file:', error);
      audioFile.downloading = false;

      let errorMessage = `Failed to download ${audioFile.name}`;
      if (error instanceof Error) {
        if (error.message.includes('CORS')) {
          errorMessage += ' - CORS error';
        } else if (error.message.includes('timeout')) {
          errorMessage += ' - timeout';
        } else if (error.message.includes('network')) {
          errorMessage += ' - network error';
        }
      }

      this.showToast(errorMessage, 'danger');
    }
  }

  async downloadAllFiles(autoPreload: boolean = false) {
    const downloadPromises = this.audioFiles
      .filter(audioFile => !audioFile.downloaded && !audioFile.downloading)
      .map(async (audioFile, index) => {
        // Stagger downloads to avoid overwhelming the server
        await new Promise(resolve => setTimeout(resolve, index * 500));
        return this.downloadAudioFile(audioFile, autoPreload);
      });

    if (downloadPromises.length > 0) {
      this.showToast(`Starting download of ${downloadPromises.length} files...`, 'primary');
      await Promise.allSettled(downloadPromises);
      const successCount = this.audioFiles.filter(f => f.downloaded).length;
      this.showToast(`Download completed: ${successCount}/${this.audioFiles.length} files ready`, 'success');
    }
  }

  async downloadAndPreloadAll() {
    await this.downloadAllFiles(true);
  }

  async deleteAudioFile(audioFile: LocalAudioFile) {
    if (!audioFile.downloaded) return;

    try {
      await Filesystem.deleteFile({
        path: `${this.AUDIO_DIRECTORY}/${audioFile.fileName}`,
        directory: Directory.Data
      });

      audioFile.downloaded = false;
      audioFile.preloaded = false;
      audioFile.size = undefined;
      audioFile.duration = undefined;
      audioFile.uri = undefined;
      audioFile.localPath = undefined;

      // Stop playback if this file is currently playing
      if (this.currentlyPlaying === audioFile.uri) {
        await this.stopPlayback();
      }

      this.showToast(`${audioFile.name} deleted`, 'medium');
    } catch (error) {
      console.error('Failed to delete audio file:', error);
      this.showToast(`Failed to delete ${audioFile.name}`, 'danger');
    }
  }

  async preloadAudio(audioFile: LocalAudioFile) {
    if (!audioFile.downloaded || audioFile.preloaded || audioFile.loading) return;

    audioFile.loading = true;
    let retryCount = 0;
    const maxRetries = 2;

    while (retryCount < maxRetries) {
      try {
        await CapacitorAudioEngine.preload({
          uri: audioFile.uri!,
          prepare: true
        });

        audioFile.preloaded = true;
        audioFile.loading = false;
        this.showToast(`${audioFile.name} preloaded successfully`, 'success');
        break; // Success, exit retry loop
      } catch (error) {
        retryCount++;
        console.error(`Failed to preload audio (attempt ${retryCount}):`, error);

        if (retryCount >= maxRetries) {
          audioFile.loading = false;
          this.showToast(`Failed to preload ${audioFile.name}`, 'danger');
        } else {
          // Wait before retry
          await new Promise(resolve => setTimeout(resolve, 1000));
        }
      }
    }
  }

  async preloadAllAudio() {
    const filesToPreload = this.audioFiles.filter(audioFile =>
      audioFile.downloaded && !audioFile.preloaded && !audioFile.loading
    );

    if (filesToPreload.length === 0) {
      this.showToast('No files available for preloading', 'warning');
      return;
    }

    this.showToast(`Starting preload of ${filesToPreload.length} files...`, 'primary');

    const preloadPromises = filesToPreload.map(async (audioFile, index) => {
      // Stagger preloads to avoid overwhelming the system
      await new Promise(resolve => setTimeout(resolve, index * 300));
      return this.preloadAudio(audioFile);
    });

    await Promise.allSettled(preloadPromises);

    const preloadedCount = this.audioFiles.filter(f => f.preloaded).length;
    this.showToast(`Preload completed: ${preloadedCount}/${this.downloadedCount} files preloaded`, 'success');
  }

  async playAudio(audioFile: LocalAudioFile) {
    if (!audioFile.downloaded || !audioFile.uri) {
      this.showToast('File not downloaded yet', 'warning');
      return;
    }

    try {
      // Stop current playback if any
      if (this.currentlyPlaying) {
        await this.stopPlayback();
      }

      // Start new playback
      await CapacitorAudioEngine.startPlayback({
        uri: audioFile.uri,
        speed: this.playbackSpeed,
        volume: this.volume,
        loop: this.isLooping,
        startTime: 0
      });

      this.currentlyPlaying = audioFile.uri;
      this.showToast(`Playing ${audioFile.name}`, 'primary');
    } catch (error) {
      console.error('Failed to play audio:', error);
      this.showToast(`Failed to play ${audioFile.name}`, 'danger');
    }
  }

  async pausePlayback() {
    try {
      await CapacitorAudioEngine.pausePlayback();
      this.showToast('Playback paused', 'medium');
    } catch (error) {
      console.error('Failed to pause playback:', error);
      this.showToast('Failed to pause playback', 'danger');
    }
  }

  async resumePlayback() {
    try {
      await CapacitorAudioEngine.resumePlayback();
      this.showToast('Playback resumed', 'primary');
    } catch (error) {
      console.error('Failed to resume playback:', error);
      this.showToast('Failed to resume playback', 'danger');
    }
  }

  async stopPlayback() {
    try {
      await CapacitorAudioEngine.stopPlayback();
      this.currentlyPlaying = null;
      this.playbackStatus = {
        status: 'stopped',
        currentTime: 0,
        duration: 0,
        position: 0
      };
      this.showToast('Playback stopped', 'medium');
    } catch (error) {
      console.error('Failed to stop playback:', error);
      this.showToast('Failed to stop playback', 'danger');
    }
  }

  async stopAllPlayback() {
    try {
      await CapacitorAudioEngine.destroyAllPlaybacks();
      this.currentlyPlaying = null;
      this.playbackStatus = {
        status: 'idle',
        currentTime: 0,
        duration: 0,
        position: 0
      };
    } catch (error) {
      console.error('Failed to stop all playback:', error);
    }
  }

  async seekTo(time: number) {
    try {
      await CapacitorAudioEngine.seekTo({ time });
    } catch (error) {
      console.error('Failed to seek:', error);
      this.showToast('Failed to seek', 'danger');
    }
  }

  onSeek(event: any) {
    const seekTime = (event.detail.value / 100) * this.playbackStatus.duration;
    this.seekTo(seekTime);
  }

  onSpeedChange() {
    // Speed change requires restarting playback with new speed
    if (this.currentlyPlaying) {
      const currentAudio = this.audioFiles.find(audio => audio.uri === this.currentlyPlaying);
      if (currentAudio) {
        this.playAudio(currentAudio);
      }
    }
  }

  onVolumeChange() {
    // Volume change requires restarting playback with new volume
    if (this.currentlyPlaying) {
      const currentAudio = this.audioFiles.find(audio => audio.uri === this.currentlyPlaying);
      if (currentAudio) {
        this.playAudio(currentAudio);
      }
    }
  }

  async getPlaybackStatus() {
    try {
      const status = await CapacitorAudioEngine.getPlaybackStatus();
      this.playbackStatus = status;
      console.log('Current playback status:', status);
    } catch (error) {
      console.error('Failed to get playback status:', error);
    }
  }

  async listStoredFiles() {
    try {
      const result = await Filesystem.readdir({
        path: this.AUDIO_DIRECTORY,
        directory: Directory.Data
      });
      console.log('Stored files:', result.files);
      this.showToast(`Found ${result.files.length} stored files`, 'primary');
    } catch (error) {
      console.error('Failed to list stored files:', error);
      this.showToast('Failed to list stored files', 'danger');
    }
  }

  async clearAllFiles() {
    try {
      for (const audioFile of this.audioFiles.filter(f => f.downloaded)) {
        await this.deleteAudioFile(audioFile);
      }
      this.showToast('All files cleared', 'medium');
    } catch (error) {
      console.error('Failed to clear all files:', error);
      this.showToast('Failed to clear all files', 'danger');
    }
  }

  formatTime(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = Math.floor(seconds % 60);
    return `${minutes}:${remainingSeconds.toString().padStart(2, '0')}`;
  }

  formatFileSize(bytes?: number): string {
    if (!bytes) return 'Unknown';
    const kb = bytes / 1024;
    if (kb < 1024) return `${kb.toFixed(1)} KB`;
    return `${(kb / 1024).toFixed(1)} MB`;
  }

  isPlaying(audioFile: LocalAudioFile): boolean {
    return this.currentlyPlaying === audioFile.uri && this.playbackStatus.status === 'playing';
  }

  isPaused(audioFile: LocalAudioFile): boolean {
    return this.currentlyPlaying === audioFile.uri && this.playbackStatus.status === 'paused';
  }

  private async removeAllListeners() {
    for (const listener of this.listeners) {
      await listener.remove();
    }
    this.listeners = [];
  }

  private async showToast(message: string, color: string) {
    // This would typically use Ionic's ToastController
    // For now, just console.log
    console.log(`[${color.toUpperCase()}] ${message}`);
  }

  async validateAudioFile(audioFile: LocalAudioFile): Promise<boolean> {
    if (!audioFile.downloaded || !audioFile.uri) return false;

    try {
      const audioInfo = await CapacitorAudioEngine.getAudioInfo({ uri: audioFile.uri });

      // Update file info if successful
      if (audioInfo.duration > 0) {
        audioFile.duration = audioInfo.duration;
        return true;
      }

      return false;
    } catch (error) {
      console.error('Audio file validation failed:', error);
      return false;
    }
  }

  async validateAllFiles() {
    const downloadedFiles = this.audioFiles.filter(f => f.downloaded);
    let validCount = 0;

    for (const audioFile of downloadedFiles) {
      const isValid = await this.validateAudioFile(audioFile);
      if (isValid) validCount++;
    }

    this.showToast(`Validation complete: ${validCount}/${downloadedFiles.length} files are valid`,
      validCount === downloadedFiles.length ? 'success' : 'warning');
  }

  async retryFailedDownloads() {
    const failedFiles = this.audioFiles.filter(f => !f.downloaded && !f.downloading);

    if (failedFiles.length === 0) {
      this.showToast('No failed downloads to retry', 'medium');
      return;
    }

    this.showToast(`Retrying ${failedFiles.length} failed downloads...`, 'primary');

    for (const audioFile of failedFiles) {
      await this.downloadAudioFile(audioFile);
      // Small delay between retries
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  }
}
