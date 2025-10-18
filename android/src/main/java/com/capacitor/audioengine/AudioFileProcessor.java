package com.capacitor.audioengine;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;
import com.getcapacitor.JSObject;
import java.io.File;
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

        // Optimize retry strategy based on file size for faster processing of large files
        long fileSize = file.length();
        int maxRetries = fileSize > 100 * 1024 * 1024 ? 3 : 5; // Fewer retries for large files (>100MB)
        long retryDelayMs = fileSize > 100 * 1024 * 1024 ? 50 : 150; // Shorter delay for large files

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

                // For large files, only get duration to minimize processing time
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

                Log.d(TAG, "Attempt " + attempt + " - Duration: " + durationStr + " (file size: " + fileSize + " bytes)");

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

            // Determine duration (us) for clamping end time
            long sourceDurationUs = -1L;
            if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                try {
                    sourceDurationUs = audioFormat.getLong(MediaFormat.KEY_DURATION);
                } catch (Exception ignored) { }
            }
            if (sourceDurationUs <= 0) {
                // Fallback via retriever helper (seconds to us)
                double durSec = getAudioDuration(sourceFile.getAbsolutePath());
                if (durSec > 0) sourceDurationUs = (long) (durSec * 1_000_000L);
            }

            long startTimeUs = Math.max(0L, (long) (startTime * 1_000_000L));
            long requestedEndUs = (long) (endTime * 1_000_000L);
            long effectiveEndUs = requestedEndUs;
            if (sourceDurationUs > 0) {
                // Clamp end time to track duration
                effectiveEndUs = Math.min(requestedEndUs, sourceDurationUs);
            }
            if (effectiveEndUs <= startTimeUs) {
                muxer.start();
                muxer.stop();
                Log.w(TAG, "Trim range is empty or invalid. startUs=" + startTimeUs + ", endUs=" + effectiveEndUs);
                return;
            }

            // Create muxer track
            int muxerTrack = muxer.addTrack(audioFormat);
            muxer.start();

            // Seek near start, then advance until we reach the desired window
            extractor.seekTo(startTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            // Choose buffer size from format if available
            int bufferSize = 64 * 1024; // default 64KB
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                try {
                    bufferSize = Math.max(bufferSize, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
                } catch (Exception ignored) { }
            }
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(bufferSize);
            android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

            long samplesProcessed = 0;
            long firstPtsUs = -1L;
            long lastPtsUs = -1L;

            // Skip any samples before the start window
            while (true) {
                int peekSize = extractor.readSampleData(buffer, 0);
                if (peekSize < 0) {
                    break; // no data
                }
                long t = extractor.getSampleTime();
                if (t >= startTimeUs) {
                    // We'll write this sample; do not advance yet so it gets processed by the main loop
                    // Reset buffer position for re-read in main loop
                    // But MediaExtractor doesn't support rewinding; so we keep current buffer and write now
                    bufferInfo.offset = 0;
                    bufferInfo.size = peekSize;
                    firstPtsUs = t;
                    long pts = 0; // rebase to start at 0
                    bufferInfo.presentationTimeUs = pts;
                    bufferInfo.flags = convertExtractorFlags(extractor.getSampleFlags());
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                    lastPtsUs = pts;
                    extractor.advance();
                    samplesProcessed++;
                    break;
                } else {
                    extractor.advance();
                }
            }

            // Main copy loop within [start, effectiveEnd]
            while (true) {
                int sampleSize = extractor.readSampleData(buffer, 0);
                if (sampleSize < 0) break;

                long sampleTime = extractor.getSampleTime();
                if (sampleTime > effectiveEndUs) break;

                bufferInfo.offset = 0;
                bufferInfo.size = sampleSize;
                if (firstPtsUs < 0) firstPtsUs = sampleTime;
                long pts = sampleTime - firstPtsUs; // rebase PTS to 0
                if (lastPtsUs >= 0 && pts <= lastPtsUs) {
                    pts = lastPtsUs + 1;
                }
                bufferInfo.presentationTimeUs = pts;
                bufferInfo.flags = convertExtractorFlags(extractor.getSampleFlags());

                muxer.writeSampleData(muxerTrack, buffer, bufferInfo);
                extractor.advance();
                lastPtsUs = pts;
                samplesProcessed++;
            }

            Log.d(TAG, "Audio trimmed successfully from " + startTime + "s to " + (effectiveEndUs / 1_000_000.0) + "s, processed " + samplesProcessed + " samples");

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
     * Get audio file information with legacy file:// URIs
     * @param filePath The file path
     */
    public static JSObject getAudioFileInfo(String filePath) {
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info - matching iOS format
            info.put("path", filePath);
            info.put("filename", file.getName());
            info.put("size", file.length());
            info.put("createdAt", file.lastModified());

            // Always use legacy file:// URI format for compatibility
            info.put("uri", "file://" + filePath);
            info.put("webPath", "capacitor://localhost/_capacitor_file_" + filePath);
            Log.d(TAG, "Generated legacy URI: file://" + filePath);

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
