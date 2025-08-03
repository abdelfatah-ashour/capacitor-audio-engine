package com.capacitor.audioengine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

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
     * Get duration of an audio file
     */
    public static double getAudioDuration(String filePath) {
        try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            retriever.setDataSource(filePath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return durationStr != null ? Double.parseDouble(durationStr) / 1000.0 : 0.0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio duration for: " + filePath, e);
            return 0.0;
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
     * Get audio file information
     */
    public static JSObject getAudioFileInfo(String filePath) {
        return getAudioFileInfo(filePath, false);
    }

    /**
     * Get audio file information with optional base64 generation
     */
    public static JSObject getAudioFileInfo(String filePath, boolean includeBase64) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info - matching iOS format
            info.put("path", filePath);
            info.put("uri", "file://" + filePath);
            info.put("webPath", "capacitor://localhost/_capacitor_file_" + filePath);
            info.put("filename", file.getName());
            info.put("size", file.length());
            info.put("createdAt", file.lastModified());

            if (file.exists()) {
                // Audio-specific info
                double duration = getAudioDuration(filePath);
                info.put("duration", duration);

                // Format info using MediaMetadataRetriever
                try (MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
                    retriever.setDataSource(filePath);

                    String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

                    info.put("mimeType", mimeType != null ? mimeType : "audio/mp4");
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
                    Log.w(TAG, "Error with MediaMetadataRetriever", e);
                }

                // Generate base64 if requested
                if (includeBase64) {
                    try {
                        String base64 = generateBase64FromFile(file);
                        info.put("base64", base64);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to generate base64 for file: " + filePath, e);
                        info.put("base64", ""); // Fallback to empty string
                    }
                } else {
                    info.put("base64", ""); // Empty for performance
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting audio file info", e);
            info.put("error", e.getMessage());
        }

        return info;
    }

    /**
     * Generate base64 data URI from audio file
     */
    private static String generateBase64FromFile(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] audioBytes = new byte[(int) file.length()];
            int bytesRead = fis.read(audioBytes);

            if (bytesRead != file.length()) {
                throw new IOException("Failed to read complete file");
            }

            String base64Data = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP);
            return "data:audio/mp4;base64," + base64Data;
        }
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
