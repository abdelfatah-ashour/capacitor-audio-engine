import { Injectable } from '@angular/core';

export interface DownloadOptions {
  url: string;
  maxRetries?: number;
  retryDelay?: number;
  timeout?: number;
}

export interface DownloadProgress {
  loaded: number;
  total: number;
  percentage: number;
}

@Injectable({
  providedIn: 'root'
})
export class AudioDownloadService {

  constructor() { }

  /**
   * Download audio file with better CORS handling and retry logic
   */
  async downloadWithRetry(options: DownloadOptions): Promise<Blob> {
    const {
      url,
      maxRetries = 3,
      retryDelay = 1000,
      timeout = 30000
    } = options;

    let lastError: Error | null = null;

    for (let attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        console.log(`Downloading attempt ${attempt}/${maxRetries}: ${url}`);

        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), timeout);

        const response = await fetch(url, {
          method: 'GET',
          mode: 'cors',
          cache: 'no-cache',
          headers: {
            'Accept': 'audio/*,*/*;q=0.9',
            'Cache-Control': 'no-cache',
            'Pragma': 'no-cache',
          },
          credentials: 'omit',
          signal: controller.signal
        });

        clearTimeout(timeoutId);

        if (!response.ok) {
          throw new Error(`HTTP ${response.status}: ${response.statusText}`);
        }

        const blob = await response.blob();

        // Validate blob
        if (blob.size === 0) {
          throw new Error('Downloaded file is empty');
        }

        // Validate MIME type
        if (!this.isValidAudioMimeType(blob.type)) {
          console.warn(`Unexpected MIME type: ${blob.type}, but continuing...`);
        }

        console.log(`Download successful: ${blob.size} bytes, type: ${blob.type}`);
        return blob;

      } catch (error) {
        lastError = error as Error;
        console.error(`Download attempt ${attempt} failed:`, error);

        if (attempt < maxRetries) {
          const delay = retryDelay * Math.pow(2, attempt - 1); // Exponential backoff
          console.log(`Retrying in ${delay}ms...`);
          await this.delay(delay);
        }
      }
    }

    throw new Error(`Download failed after ${maxRetries} attempts. Last error: ${lastError?.message}`);
  }

  /**
   * Check if the MIME type is a valid audio format
   */
  private isValidAudioMimeType(mimeType: string): boolean {
    const validTypes = [
      'audio/mpeg',
      'audio/mp3',
      'audio/wav',
      'audio/ogg',
      'audio/aac',
      'audio/mp4',
      'audio/x-m4a',
      'audio/webm',
      'audio/flac'
    ];

    return validTypes.some(type => mimeType.toLowerCase().includes(type));
  }

  /**
   * Convert blob to base64 with proper error handling
   */
  async blobToBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();

      reader.onload = () => {
        if (typeof reader.result === 'string') {
          resolve(reader.result);
        } else {
          reject(new Error('Failed to convert blob to base64'));
        }
      };

      reader.onerror = () => {
        reject(new Error('FileReader error: ' + reader.error?.message));
      };

      reader.readAsDataURL(blob);
    });
  }

  /**
   * Utility function to create a delay
   */
  private delay(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
  }

  /**
   * Check if a URL is accessible (basic connectivity test)
   */
  async checkUrlAccessibility(url: string): Promise<boolean> {
    try {
      const response = await fetch(url, {
        method: 'HEAD',
        mode: 'cors',
        cache: 'no-cache',
        credentials: 'omit'
      });
      return response.ok;
    } catch (error) {
      console.error('URL accessibility check failed:', error);
      return false;
    }
  }

  /**
   * Get file size without downloading the entire file
   */
  async getFileSize(url: string): Promise<number | null> {
    try {
      const response = await fetch(url, {
        method: 'HEAD',
        mode: 'cors',
        credentials: 'omit'
      });

      if (response.ok) {
        const contentLength = response.headers.get('content-length');
        return contentLength ? parseInt(contentLength, 10) : null;
      }
    } catch (error) {
      console.error('Failed to get file size:', error);
    }
    return null;
  }
}
