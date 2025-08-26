/**
 * Utility functions for handling compressed base64 data from iOS audio recordings
 *
 * iOS returns base64 data in the following formats:
 * - "data:audio/m4a;base64,lzfse:COMPRESSED_DATA" (LZFSE compressed)
 * - "data:audio/m4a;base64,UNCOMPRESSED_DATA" (regular base64)
 */

export interface CompressedBase64Info {
  isCompressed: boolean;
  algorithm?: 'lzfse';
  data: string;
  mimeType: string;
}

/**
 * Parse base64 data to determine if it's compressed and extract metadata
 */
export function parseBase64Data(base64String: string): CompressedBase64Info {
  if (!base64String.startsWith('data:')) {
    return {
      isCompressed: false,
      data: base64String,
      mimeType: 'audio/m4a',
    };
  }

  const [header, data] = base64String.split(',', 2);

  if (header.includes('lzfse:')) {
    return {
      isCompressed: true,
      algorithm: 'lzfse',
      data: data,
      mimeType: header.split(';')[0].replace('data:', ''),
    };
  }

  return {
    isCompressed: false,
    data: data,
    mimeType: header.split(';')[0].replace('data:', ''),
  };
}

/**
 * Get the raw base64 data without compression metadata
 */
export function getRawBase64Data(base64String: string): string {
  const parsed = parseBase64Data(base64String);
  return parsed.data;
}

/**
 * Check if base64 data is compressed
 */
export function isCompressedBase64(base64String: string): boolean {
  return parseBase64Data(base64String).isCompressed;
}

/**
 * Get compression information for logging/debugging
 */
export function getCompressionInfo(base64String: string): {
  isCompressed: boolean;
  algorithm?: string;
  estimatedOriginalSize?: number;
  compressedSize?: number;
} {
  const parsed = parseBase64Data(base64String);

  if (!parsed.isCompressed) {
    return { isCompressed: false };
  }

  // Estimate sizes (base64 encoding adds ~33% overhead)
  const compressedSize = Math.floor((parsed.data.length * 3) / 4);

  return {
    isCompressed: true,
    algorithm: parsed.algorithm,
    compressedSize,
  };
}

/**
 * Convert base64 string to Blob for file operations
 */
export function base64ToBlob(base64String: string): Blob {
  const parsed = parseBase64Data(base64String);

  // Note: If data is compressed, you would need to decompress it first
  // For now, we assume decompression is handled by the native layer or not needed
  const byteCharacters = atob(parsed.data);
  const byteNumbers = new Array(byteCharacters.length);

  for (let i = 0; i < byteCharacters.length; i++) {
    byteNumbers[i] = byteCharacters.charCodeAt(i);
  }

  const byteArray = new Uint8Array(byteNumbers);
  return new Blob([byteArray], { type: parsed.mimeType });
}

/**
 * Convert base64 string to data URL for direct use in audio elements
 */
export function getDataURL(base64String: string): string {
  // If already a data URL, return as-is
  if (base64String.startsWith('data:')) {
    return base64String;
  }

  // Convert plain base64 to data URL
  return `data:audio/m4a;base64,${base64String}`;
}
