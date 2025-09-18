package com.capacitor.audioengine;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Handles audio file processing operations including trimming and merging
 */
public class AudioFileProcessor {
    private static final String TAG = "AudioFileProcessor";



    /**
     * Get duration of an audio file with retry mechanism for newly created files
     */
    public static double getAudioDuration(String filePath) {
        File file = new File(filePath);

        // Check if file exists and is not empty
        if (!file.exists() || file.length() == 0) {
            Log.w(TAG, "File does not exist or is empty: " + filePath);
            return 0.0;
        }

        Log.d(TAG, "Attempting to get duration for file: " + file.getName() + " (size: " + file.length() + " bytes)");

        // First try with MediaMetadataRetriever with retry mechanism
        double duration = getAudioDurationWithRetriever(filePath);
        if (duration > 0) {
            return duration;
        }

        // If MetadataRetriever fails, try with MediaExtractor as fallback
        Log.w(TAG, "MediaMetadataRetriever failed, trying MediaExtractor for: " + file.getName());
        duration = getAudioDurationWithExtractor(filePath);
        if (duration > 0) {
            Log.d(TAG, "Successfully got duration using MediaExtractor: " + duration + "s for " + file.getName());
            return duration;
        }

        Log.e(TAG, "All duration calculation methods failed for: " + file.getName());
        return 0.0;
    }

    /**
     * Get duration using MediaMetadataRetriever with retry mechanism
     */
    private static double getAudioDurationWithRetriever(String filePath) {
        File file = new File(filePath);
        int maxRetries = 5; // Increased retries
        long retryDelayMs = 150; // Increased initial delay

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            MediaMetadataRetriever retriever = null;
            try {
                // Ensure file is fully written before attempting to read metadata
                if (attempt > 1) {
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Duration calculation interrupted");
                        return 0.0;
                    }
                }

                retriever = new MediaMetadataRetriever();
                retriever.setDataSource(filePath);

                // Try to get multiple metadata fields to verify file integrity
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

                Log.d(TAG, "Attempt " + attempt + " - Duration: " + durationStr + ", MIME: " + mimeType + ", Bitrate: " + bitrate);

                if (durationStr != null && !durationStr.isEmpty()) {
                    try {
                        double duration = Double.parseDouble(durationStr) / 1000.0;
                        if (duration > 0) {
                            Log.d(TAG, "Successfully got duration: " + duration + "s for " + file.getName() +
                                  " (attempt " + attempt + "/" + maxRetries + ")");
                            return duration;
                        }
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid duration format: " + durationStr);
                    }
                }

                Log.w(TAG, "Got null or empty duration metadata on attempt " + attempt + " for: " + file.getName());

            } catch (Exception e) {
                Log.w(TAG, "Attempt " + attempt + "/" + maxRetries + " failed to get audio duration for: " +
                      file.getName() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage());

                if (attempt == maxRetries) {
                    Log.e(TAG, "All MetadataRetriever attempts failed for: " + file.getName(), e);
                }

                // Increase delay for next retry
                retryDelayMs = Math.min(retryDelayMs * 2, 1000); // Cap at 1 second
            } finally {
                if (retriever != null) {
                    try {
                        retriever.release();
                    } catch (Exception e) {
                        Log.w(TAG, "Error releasing MediaMetadataRetriever", e);
                    }
                }
            }
        }

        return 0.0;
    }

    /**
     * Alternative duration calculation using MediaExtractor
     */
    private static double getAudioDurationWithExtractor(String filePath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            // Find audio track
            int audioTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    break;
                }
            }

            if (audioTrack == -1) {
                Log.w(TAG, "No audio track found in file using MediaExtractor");
                return 0.0;
            }

            MediaFormat format = extractor.getTrackFormat(audioTrack);
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                long durationUs = format.getLong(MediaFormat.KEY_DURATION);
                double durationSeconds = durationUs / 1_000_000.0;
                Log.d(TAG, "MediaExtractor found duration: " + durationSeconds + "s");
                return durationSeconds;
            } else {
                Log.w(TAG, "MediaExtractor: audio track has no duration metadata");
                return 0.0;
            }

        } catch (Exception e) {
            Log.e(TAG, "MediaExtractor failed to get duration", e);
            return 0.0;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaExtractor", e);
                }
            }
        }
    }

    /**
     * Trim audio file from startTime to endTime
     */
    public static void trimAudioFile(File sourceFile, File outputFile, double startTime, double endTime) throws IOException {
        try (ResourceManager.SafeMediaExtractor safeExtractor = new ResourceManager.SafeMediaExtractor();
             ResourceManager.SafeMediaMuxer safeMuxer = new ResourceManager.SafeMediaMuxer(
                 outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) {

            MediaExtractor extractor = safeExtractor.getExtractor();
            MediaMuxer muxer = safeMuxer.getMuxer();

            extractor.setDataSource(sourceFile.getAbsolutePath());

            // Find audio track
            int audioTrack = findAudioTrack(extractor);
            if (audioTrack == -1) {
                throw new IOException("No audio track found in file: " + sourceFile.getAbsolutePath());
            }

            MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);
            extractor.selectTrack(audioTrack);

            // Create muxer track
            int muxerTrack = muxer.addTrack(audioFormat);
            muxer.start();

            // Seek to start time
            extractor.seekTo((long)(startTime * 1000000), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Extract and write data with smaller buffer for better memory management
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024); // 64KB
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            long endTimeUs = (long)(endTime * 1000000);
            long samplesProcessed = 0;

            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long sampleTime = extractor.getSampleTime();
                if (sampleTime > endTimeUs) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                bufferInfo.presentationTimeUs = sampleTime;
                bufferInfo.flags = convertExtractorFlags(extractor.getSampleFlags());

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                extractor.advance();
                samplesProcessed++;
            }

            Log.d(TAG, "Audio trimmed successfully from " + startTime + "s to " + endTime + "s, processed " + samplesProcessed + " samples");

        } catch (Exception e) {
            // Clean up output file if trimming failed
            if (outputFile.exists()) {
                boolean deleted = outputFile.delete();
                if (!deleted) {
                    Log.w(TAG, "Failed to delete output file after trim failure: " + outputFile.getAbsolutePath());
                }
            }
            throw new IOException("Failed to trim audio file: " + e.getMessage(), e);
        }
    }

    /**
     * Find audio track in MediaExtractor
     */
    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Convert MediaExtractor flags to MediaCodec BufferInfo flags
     */
    private static int convertExtractorFlags(int extractorFlags) {
        int bufferFlags = 0;
        if ((extractorFlags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            bufferFlags |= android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
        }
        return bufferFlags;
    }

    /**
     * Get audio file information with secure FileProvider URIs
     */
    public static JSObject getAudioFileInfo(String filePath) {
        return getAudioFileInfo(filePath, null);
    }

    /**
     * Get audio file information with secure FileProvider URIs
     * @param filePath The file path
     * @param context The context for FileProvider (optional, will use file:// if null)
     */
    public static JSObject getAudioFileInfo(String filePath, Context context) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info - matching iOS format
            info.put("path", filePath);
            info.put("filename", file.getName());
            info.put("size", file.length());
            info.put("createdAt", file.lastModified());

            // Generate secure URI using FileProvider if context is available
            if (context != null) {
                try {
                    Uri secureUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        file
                    );
                    info.put("uri", secureUri.toString());
                    info.put("webPath", secureUri.toString());
                    Log.d(TAG, "Generated secure URI: " + secureUri.toString());
                } catch (Exception e) {
                    Log.w(TAG, "Failed to generate FileProvider URI, falling back to file://", e);
                    // Fallback to file:// URI
                    info.put("uri", "file://" + filePath);
                    info.put("webPath", "capacitor://localhost/_capacitor_file_" + filePath);
                }
            } else {
                // Legacy file:// URI when context is not available
                info.put("uri", "file://" + filePath);
                info.put("webPath", "capacitor://localhost/_capacitor_file_" + filePath);
            }

            if (file.exists() && file.length() > 0) {
                // First validate file integrity
                if (!isValidAudioFile(filePath)) {
                    Log.w(TAG, "Audio file appears to be corrupted or invalid: " + file.getName());
                    info.put("duration", 0.0);
                    info.put("error", "Invalid or corrupted audio file");
                } else {
                    // Audio-specific info
                    double duration = getAudioDuration(filePath);
                    info.put("duration", duration);
                }

                // Format info using MediaMetadataRetriever
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(filePath);

                    String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

                    // Always use audio/m4a for consistent MIME type across platforms
                    info.put("mimeType", "audio/m4a");
                    info.put("bitrate", bitrate != null ? Integer.parseInt(bitrate) : 0);

                    // Get number of channels using compatibility method
                    info.put("channels", getAudioChannelsCompat(filePath));

                    // METADATA_KEY_SAMPLER requires API level 31
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        String sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                        info.put("sampleRate", sampleRate != null ? Integer.parseInt(sampleRate) : 0);
                    } else {
                        // For older API levels, try to get sample rate using MediaExtractor
                        info.put("sampleRate", getAudioSampleRateCompat(filePath));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error with MediaMetadataRetriever for file info", e);
                    // Set default values if metadata retrieval fails
                    info.put("mimeType", "audio/m4a");
                    info.put("bitrate", 0);
                    info.put("channels", 1);
                    info.put("sampleRate", 0);
                }
            } else {
                // Empty or non-existent file
                info.put("duration", 0.0);
                info.put("mimeType", "audio/m4a");
                info.put("bitrate", 0);
                info.put("channels", 1);
                info.put("sampleRate", 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting audio file info", e);
            info.put("error", e.getMessage());
            info.put("duration", 0.0);
        }

        return info;
    }

    /**
     * Validate if an audio file is properly formatted and readable
     */
    private static boolean isValidAudioFile(String filePath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            // Check if file has at least one audio track
            boolean hasAudioTrack = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    hasAudioTrack = true;

                    // Try to select and read at least one sample to verify integrity
                    try {
                        extractor.selectTrack(i);
                        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024);
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize > 0) {
                            Log.d(TAG, "Audio file validation passed: " + new File(filePath).getName());
                            return true;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error reading sample data during validation", e);
                    }
                    break;
                }
            }

            if (!hasAudioTrack) {
                Log.w(TAG, "No audio track found in file: " + new File(filePath).getName());
            }

        } catch (Exception e) {
            Log.w(TAG, "Audio file validation failed for: " + new File(filePath).getName(), e);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing extractor during validation", e);
                }
            }
        }

        return false;
    }

    /**
     * Get audio channels for API levels below 29 using MediaExtractor
     */
    private static int getAudioChannelsCompat(String filePath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        return format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get channel count using MediaExtractor", e);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaExtractor", e);
                }
            }
        }
        return 1; // Default to mono
    }

    /**
     * Get audio sample rate for API levels below 31 using MediaExtractor
     */
    private static int getAudioSampleRateCompat(String filePath) {
        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(filePath);

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);

                if (mime != null && mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        return format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get sample rate using MediaExtractor", e);
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing MediaExtractor", e);
                }
            }
        }
        return 0;
    }
}
