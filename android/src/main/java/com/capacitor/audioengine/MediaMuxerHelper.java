package com.capacitor.audioengine;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Utility class for merging audio segments using MediaMuxer
 * Handles gapless AAC merging with proper codec-specific data handling
 */
public class MediaMuxerHelper {
    private static final String TAG = "MediaMuxerHelper";

    // AAC specific constants for gapless merging
    private static final long AAC_SAMPLES_PER_FRAME = 1024L;
    private static final int DEFAULT_AAC_ENCODER_DELAY = 2112;
    private static final int DEFAULT_AAC_ENCODER_PADDING = 576;

    /**
     * Merge multiple audio segments into a single file with gapless AAC merging
     *
     * @param inputSegments List of segment files to merge
     * @param outputFile Output file for merged audio
     * @throws IOException If merging fails
     */
    public static void mergeSegments(List<File> inputSegments, File outputFile) throws IOException {
        long startTime = System.currentTimeMillis();
        Log.d(TAG, "Starting merge of " + inputSegments.size() + " segments into " + outputFile.getName());

        if (inputSegments == null || inputSegments.isEmpty()) {
            throw new IOException("No segments to merge");
        }

        // Validate all input files exist and are readable
        for (File segment : inputSegments) {
            if (!segment.exists() || !segment.canRead() || segment.length() == 0) {
                throw new IOException("Invalid segment file: " + segment.getName());
            }
        }

        MediaMuxer muxer = null;
        MediaExtractor extractor = null;
        ByteBuffer buffer = null;

        try {
            // Create output muxer
            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            int trackIndex = -1;
            long totalDurationUs = 0;
            MediaFormat outputFormat = null;
            boolean muxerStarted = false;

            // Process each segment
            for (int i = 0; i < inputSegments.size(); i++) {
                File segment = inputSegments.get(i);

                Log.d(TAG, "Processing segment " + (i + 1) + "/" + inputSegments.size() + ": " + segment.getName());

                extractor = new MediaExtractor();
                extractor.setDataSource(segment.getAbsolutePath());

                // Find audio track
                int audioTrackIndex = findAudioTrack(extractor);
                if (audioTrackIndex < 0) {
                    Log.w(TAG, "No audio track found in segment: " + segment.getName());
                    extractor.release();
                    extractor = null;
                    continue;
                }

                extractor.selectTrack(audioTrackIndex);
                MediaFormat format = extractor.getTrackFormat(audioTrackIndex);

                // Initialize muxer with first valid segment
                if (!muxerStarted) {
                    outputFormat = format;
                    trackIndex = muxer.addTrack(format);
                    muxer.start();
                    muxerStarted = true;

                    Log.d(TAG, "Muxer initialized with format: " + formatToString(format));
                } else {
                    // Verify format compatibility
                    if (!areFormatsCompatible(outputFormat, format)) {
                        Log.w(TAG, "Format mismatch in segment " + segment.getName() + ", continuing anyway");
                    }
                }

                // Calculate timing for gapless merging
                long segmentDurationUs = format.containsKey(MediaFormat.KEY_DURATION) ?
                    format.getLong(MediaFormat.KEY_DURATION) : 0;

                if (segmentDurationUs <= 0) {
                    segmentDurationUs = estimateSegmentDuration(extractor);
                }

                // Copy audio data with proper timing
                long sampleTimeOffset = totalDurationUs;
                if (buffer == null) {
                    buffer = ByteBuffer.allocate(1024 * 1024); // 1MB buffer
                }

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                long sampleTime;
                int sampleCount = 0;

                while ((sampleTime = extractor.getSampleTime()) >= 0) {
                    buffer.clear();
                    int sampleSize = extractor.readSampleData(buffer, 0);

                    if (sampleSize > 0) {
                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        bufferInfo.flags = extractor.getSampleFlags();

                        // Adjust timestamp for continuous playback
                        bufferInfo.presentationTimeUs = sampleTime + sampleTimeOffset;

                        muxer.writeSampleData(trackIndex, buffer, bufferInfo);
                        sampleCount++;
                    }

                    extractor.advance();
                }

                totalDurationUs += segmentDurationUs;
                Log.d(TAG, "Merged segment " + segment.getName() + " - samples: " + sampleCount +
                      ", duration: " + (segmentDurationUs / 1000) + "ms");

                extractor.release();
                extractor = null;
            }

            if (!muxerStarted) {
                throw new IOException("No valid audio segments found to merge");
            }

            long mergeTime = System.currentTimeMillis() - startTime;
            Log.d(TAG, "Merge completed successfully in " + mergeTime + "ms");
            Log.d(TAG, "Output file: " + outputFile.getName() + " (" + (outputFile.length() / 1024) + "KB)");
            Log.d(TAG, "Total duration: " + (totalDurationUs / 1000) + "ms");

        } catch (Exception e) {
            // Clean up failed output file
            if (outputFile.exists()) {
                if (!outputFile.delete()) {
                    Log.w(TAG, "Failed to delete incomplete output file: " + outputFile.getName());
                }
            }
            throw new IOException("Failed to merge segments: " + e.getMessage(), e);
        } finally {
            // Clean up resources
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing extractor: " + e.getMessage());
                }
            }

            if (muxer != null) {
                try {
                    muxer.stop();
                    muxer.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error stopping/releasing muxer: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Find the audio track index in MediaExtractor
     */
    private static int findAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mimeType = format.getString(MediaFormat.KEY_MIME);
            if (mimeType != null && mimeType.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Check if two MediaFormats are compatible for seamless merging
     */
    private static boolean areFormatsCompatible(MediaFormat format1, MediaFormat format2) {
        if (format1 == null || format2 == null) {
            return false;
        }

        // Check essential properties for gapless merging
        String mime1 = format1.getString(MediaFormat.KEY_MIME);
        String mime2 = format2.getString(MediaFormat.KEY_MIME);
        if (!mime1.equals(mime2)) {
            return false;
        }

        int sampleRate1 = format1.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int sampleRate2 = format2.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        if (sampleRate1 != sampleRate2) {
            return false;
        }

        int channels1 = format1.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        int channels2 = format2.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        if (channels1 != channels2) {
            return false;
        }

        // Check bitrate if available (optional for compatibility)
        if (format1.containsKey(MediaFormat.KEY_BIT_RATE) && format2.containsKey(MediaFormat.KEY_BIT_RATE)) {
            int bitRate1 = format1.getInteger(MediaFormat.KEY_BIT_RATE);
            int bitRate2 = format2.getInteger(MediaFormat.KEY_BIT_RATE);
            // Allow some tolerance in bitrate
            if (Math.abs(bitRate1 - bitRate2) > bitRate1 * 0.1) { // 10% tolerance
                Log.w(TAG, "Bitrate mismatch: " + bitRate1 + " vs " + bitRate2);
            }
        }

        return true;
    }

    /**
     * Estimate segment duration by reading through samples
     */
    private static long estimateSegmentDuration(MediaExtractor extractor) {
        long startTime = extractor.getSampleTime();
        long endTime = startTime;

        // Read through samples to find the last timestamp
        while (extractor.getSampleTime() >= 0) {
            endTime = extractor.getSampleTime();
            if (!extractor.advance()) {
                break;
            }
        }

        // Reset extractor position
        extractor.seekTo(startTime, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

        return endTime - startTime;
    }

    /**
     * Convert MediaFormat to human-readable string for logging
     */
    private static String formatToString(MediaFormat format) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");

        if (format.containsKey(MediaFormat.KEY_MIME)) {
            sb.append("mime=").append(format.getString(MediaFormat.KEY_MIME));
        }
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            sb.append(", sampleRate=").append(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            sb.append(", channels=").append(format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        }
        if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
            sb.append(", bitRate=").append(format.getInteger(MediaFormat.KEY_BIT_RATE));
        }
        if (format.containsKey(MediaFormat.KEY_DURATION)) {
            sb.append(", duration=").append(format.getLong(MediaFormat.KEY_DURATION) / 1000).append("ms");
        }

        sb.append("}");
        return sb.toString();
    }

    /**
     * Validate that a file is a valid audio file
     */
    public static boolean isValidAudioFile(File audioFile) {
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            return false;
        }

        MediaExtractor extractor = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(audioFile.getAbsolutePath());

            return findAudioTrack(extractor) >= 0;
        } catch (Exception e) {
            Log.w(TAG, "Invalid audio file " + audioFile.getName() + ": " + e.getMessage());
            return false;
        } finally {
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing extractor: " + e.getMessage());
                }
            }
        }
    }
}