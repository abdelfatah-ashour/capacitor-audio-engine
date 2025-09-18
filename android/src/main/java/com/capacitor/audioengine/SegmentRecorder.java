package com.capacitor.audioengine;

import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Encapsulates MediaRecorder for individual audio segments
 * Handles start, stop, and file management for a single segment
 */
public class SegmentRecorder {
    private static final String TAG = "SegmentRecorder";

    private MediaRecorder recorder;
    private File segmentFile;
    private boolean isRecording = false;
    private long startTimeMs = 0;
    private AudioRecordingConfig config;

    /**
     * Constructor
     */
    public SegmentRecorder() {
        // Empty constructor
    }

    /**
     * Start recording a new segment
     * @param segmentFile The file to record to
     * @param config Audio recording configuration
     * @throws IOException if recording fails to start
     */
    public void startRecording(File segmentFile, AudioRecordingConfig config) throws IOException {
        if (isRecording) {
            throw new IllegalStateException("Already recording");
        }

        this.segmentFile = segmentFile;
        this.config = config;
        this.startTimeMs = System.currentTimeMillis();

        Log.d(TAG, "Starting segment recording: " + segmentFile.getName());

        try {
            // Create and configure MediaRecorder
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(config.getSampleRate());
            recorder.setAudioEncodingBitRate(config.getBitrate());
            recorder.setOutputFile(segmentFile.getAbsolutePath());

            recorder.prepare();
            recorder.start();

            isRecording = true;
            Log.d(TAG, "Segment recording started successfully: " + segmentFile.getName());

        } catch (Exception e) {
            Log.e(TAG, "Failed to start segment recording", e);
            cleanup();
            throw new IOException("Failed to start segment recording", e);
        }
    }

    /**
     * Stop recording the current segment
     * @return The segment file that was recorded
     * @throws IOException if stopping fails
     */
    public File stopRecording() throws IOException {
        if (!isRecording) {
            Log.w(TAG, "Not currently recording");
            return segmentFile;
        }

        Log.d(TAG, "Stopping segment recording: " + segmentFile.getName());

        try {
            if (recorder != null) {
                recorder.stop();
                Log.d(TAG, "Segment recording stopped successfully: " + segmentFile.getName());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error stopping recorder", e);
            // Continue with cleanup even if stop fails
        } finally {
            cleanup();
        }

        return segmentFile;
    }

    /**
     * Check if currently recording
     * @return true if recording, false otherwise
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Get the current segment file
     * @return The segment file being recorded to
     */
    public File getSegmentFile() {
        return segmentFile;
    }

    /**
     * Get the duration of the current recording
     * @return Duration in milliseconds
     */
    public long getRecordingDuration() {
        if (!isRecording || startTimeMs == 0) {
            return 0;
        }
        return System.currentTimeMillis() - startTimeMs;
    }

    /**
     * Get the segment file size
     * @return File size in bytes, or 0 if file doesn't exist
     */
    public long getFileSize() {
        if (segmentFile != null && segmentFile.exists()) {
            return segmentFile.length();
        }
        return 0;
    }

    /**
     * Check if the segment file is valid (exists and has content)
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return segmentFile != null && segmentFile.exists() && segmentFile.length() > 0;
    }

    /**
     * Clean up resources
     */
    private void cleanup() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing recorder", e);
            }
            recorder = null;
        }
        isRecording = false;
    }

    /**
     * Release all resources and clean up
     */
    public void release() {
        cleanup();
        segmentFile = null;
        config = null;
        startTimeMs = 0;
    }
}
