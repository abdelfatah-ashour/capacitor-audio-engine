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
import java.util.List;

/**
 * Handles audio file processing operations including trimming and merging
 */
public class AudioFileProcessor {
    private static final String TAG = "AudioFileProcessor";



    /**
     * Get duration of an audio file
     */
    public static double getAudioDuration(String filePath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return durationStr != null ? Double.parseDouble(durationStr) / 1000.0 : 0.0;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get audio duration for: " + filePath, e);
            return 0.0;
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





    private static String concatenateAudioFiles(List<String> inputFiles, File outputDir, String outputFileName) throws Exception {
        Log.d(TAG, "Concatenating " + inputFiles.size() + " audio files");

        File outputFile = new File(outputDir, outputFileName);

        try (ResourceManager.SafeMediaMuxer safeMuxer = new ResourceManager.SafeMediaMuxer(
                outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)) {

            MediaMuxer muxer = safeMuxer.getMuxer();
            int audioTrackIndex = -1;
            long totalPresentationTimeUs = 0;

            for (int fileIndex = 0; fileIndex < inputFiles.size(); fileIndex++) {
                String inputFile = inputFiles.get(fileIndex);
                Log.d(TAG, "Processing file " + (fileIndex + 1) + "/" + inputFiles.size() + ": " + new File(inputFile).getName());

                try (ResourceManager.SafeMediaExtractor safeExtractor = new ResourceManager.SafeMediaExtractor()) {
                    MediaExtractor extractor = safeExtractor.getExtractor();
                    extractor.setDataSource(inputFile);

                    int audioTrack = findAudioTrack(extractor);
                    if (audioTrack == -1) {
                        Log.w(TAG, "No audio track found in file: " + inputFile);
                        continue;
                    }

                    MediaFormat audioFormat = extractor.getTrackFormat(audioTrack);
                    extractor.selectTrack(audioTrack);

                    // Add track to muxer (only for first file)
                    if (audioTrackIndex == -1) {
                        audioTrackIndex = muxer.addTrack(audioFormat);
                        muxer.start();
                    }

                    // Copy audio data
                    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(64 * 1024);
                    android.media.MediaCodec.BufferInfo bufferInfo = new android.media.MediaCodec.BufferInfo();

                    while (true) {
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) break;

                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        bufferInfo.presentationTimeUs = totalPresentationTimeUs + extractor.getSampleTime();
                        bufferInfo.flags = convertExtractorFlags(extractor.getSampleFlags());

                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo);
                        extractor.advance();
                    }

                    // Update total presentation time for next file
                    double fileDuration = getAudioDuration(inputFile);
                    totalPresentationTimeUs += (long)(fileDuration * 1000000);
                }
            }

            Log.d(TAG, "Audio concatenation completed to: " + outputFile.getName());
            return outputFile.getAbsolutePath();
        }
    }

    private static void concatenateAudioFiles(List<String> inputFiles, File outputFile) throws Exception {
        concatenateAudioFiles(inputFiles, outputFile.getParentFile(), outputFile.getName());
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
                outputFile.delete();
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
        File file = new File(filePath);
        JSObject info = new JSObject();

        try {
            // Basic file info
            info.put("uri", "file://" + filePath);
            info.put("path", filePath);
            info.put("name", file.getName());
            info.put("size", file.length());
            info.put("exists", file.exists());

            if (file.exists()) {
                // Audio-specific info
                double duration = getAudioDuration(filePath);
                info.put("duration", duration);

                // Format info using MediaMetadataRetriever
                MediaMetadataRetriever retriever = null;
                try {
                    retriever = new MediaMetadataRetriever();
                    retriever.setDataSource(filePath);

                    String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
                    String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

                    info.put("mimeType", mimeType != null ? mimeType : "audio/mp4");
                    info.put("bitrate", bitrate != null ? Integer.parseInt(bitrate) : 0);

                    // METADATA_KEY_SAMPLER requires API level 31
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        String sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
                        info.put("sampleRate", sampleRate != null ? Integer.parseInt(sampleRate) : 0);
                    } else {
                        // For older API levels, try to get sample rate using MediaExtractor
                        info.put("sampleRate", getAudioSampleRateCompat(filePath));
                    }
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
        } catch (Exception e) {
            Log.e(TAG, "Error getting audio file info", e);
            info.put("error", e.getMessage());
        }

        return info;
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
