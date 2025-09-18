package com.capacitor.audioengine;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAV audio recorder using AudioRecord for direct PCM recording
 * Provides faster stop times and more control over audio format
 */
public class WavAudioRecorder {
    private static final String TAG = "WavAudioRecorder";

    // Recording state
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private File outputFile;

    // Audio configuration
    private final int sampleRate;
    private final int channels;
    private final int audioFormat;
    private int bufferSize;

    // WAV file tracking
    private FileOutputStream fileOutputStream;
    private long totalAudioLen = 0;
    private long totalDataLen = 0;

    /**
     * Create a new WAV audio recorder
     * @param sampleRate Sample rate in Hz (e.g., 44100)
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param outputFile Output WAV file
     */
    public WavAudioRecorder(int sampleRate, int channels, File outputFile) throws IOException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.outputFile = outputFile;
        this.audioFormat = AudioFormat.ENCODING_PCM_16BIT;

        // Calculate optimal buffer size
        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        this.bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new IOException("Unable to get valid buffer size for AudioRecord");
        }

        // Use a larger buffer for better performance (4x minimum)
        this.bufferSize *= 4;

        Log.d(TAG, "WavAudioRecorder configured: " + sampleRate + "Hz, " + channels + " channels, buffer: " + bufferSize);
    }

    /**
     * Start recording to WAV file
     */
    public void startRecording() throws IOException {
        if (isRecording) {
            throw new IllegalStateException("Recording already in progress");
        }

        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;

        // Create AudioRecord
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IOException("AudioRecord initialization failed");
        }

        // Prepare output file
        fileOutputStream = new FileOutputStream(outputFile);
        writeWavHeader(fileOutputStream, 0); // Write placeholder header

        totalAudioLen = 0;
        totalDataLen = 0;
        isRecording = true;

        // Start recording thread
        recordingThread = new Thread(this::recordingLoop, "WavRecordingThread");
        recordingThread.start();

        Log.d(TAG, "WAV recording started to: " + outputFile.getName());
    }

    /**
     * Stop recording and finalize WAV file
     */
    public void stopRecording() throws IOException {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        // Wait for recording thread to finish
        if (recordingThread != null) {
            try {
                recordingThread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                Log.w(TAG, "Recording thread join interrupted", e);
                Thread.currentThread().interrupt();
            }
        }

        // Stop AudioRecord
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioRecord", e);
            }
            audioRecord = null;
        }

        // Close file stream
        if (fileOutputStream != null) {
            try {
                fileOutputStream.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing file stream", e);
            }
            fileOutputStream = null;
        }

        // Update WAV header with actual file size
        updateWavHeader(outputFile);

        Log.d(TAG, "WAV recording stopped. File size: " + outputFile.length() + " bytes, audio duration: " +
              String.format("%.2f", (totalAudioLen / (float)(sampleRate * channels * 2)) + "s"));
    }

    /**
     * Check if currently recording
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * Get current recording duration in milliseconds
     */
    public long getRecordingDuration() {
        if (totalAudioLen == 0) return 0;
        // Duration = samples / (sampleRate * channels) * 1000
        return (totalAudioLen * 1000L) / (sampleRate * channels * 2); // 2 bytes per sample for 16-bit
    }

    /**
     * Main recording loop
     */
    private void recordingLoop() {
        byte[] buffer = new byte[bufferSize];

        try {
            audioRecord.startRecording();

            while (isRecording) {
                int bytesRead = audioRecord.read(buffer, 0, bufferSize);

                if (bytesRead > 0) {
                    // Write audio data to file
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalAudioLen += bytesRead;
                    totalDataLen += bytesRead;
                } else if (bytesRead < 0) {
                    Log.w(TAG, "AudioRecord read error: " + bytesRead);
                    break;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in recording loop", e);
        } finally {
            try {
                if (audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception e) {
                Log.w(TAG, "Error stopping AudioRecord in loop", e);
            }
        }

        Log.d(TAG, "Recording loop ended. Total audio data: " + totalAudioLen + " bytes");
    }

    /**
     * Write WAV file header
     */
    private void writeWavHeader(FileOutputStream out, long audioLength) throws IOException {
        long totalDataLen = audioLength + 36;
        long longSampleRate = sampleRate;
        int byteRate = sampleRate * channels * 2; // 16-bit = 2 bytes per sample

        byte[] header = new byte[44];

        // RIFF header
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';

        // File size (will be updated later)
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);

        // WAVE header
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';

        // fmt subchunk
        header[12] = 'f';
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';

        // Subchunk1 size (16 for PCM)
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;

        // Audio format (1 for PCM)
        header[20] = 1;
        header[21] = 0;

        // Number of channels
        header[22] = (byte) channels;
        header[23] = 0;

        // Sample rate
        header[24] = (byte) (longSampleRate & 0xff);
        header[25] = (byte) ((longSampleRate >> 8) & 0xff);
        header[26] = (byte) ((longSampleRate >> 16) & 0xff);
        header[27] = (byte) ((longSampleRate >> 24) & 0xff);

        // Byte rate
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);

        // Block align
        header[32] = (byte) (channels * 2);
        header[33] = 0;

        // Bits per sample
        header[34] = 16;
        header[35] = 0;

        // data subchunk
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';

        // Data size (will be updated later)
        header[40] = (byte) (audioLength & 0xff);
        header[41] = (byte) ((audioLength >> 8) & 0xff);
        header[42] = (byte) ((audioLength >> 16) & 0xff);
        header[43] = (byte) ((audioLength >> 24) & 0xff);

        out.write(header, 0, 44);
    }

    /**
     * Update WAV header with actual file sizes
     */
    private void updateWavHeader(File wavFile) {
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "rw")) {
            // Update file size (total file size - 8)
            long fileSize = wavFile.length() - 8;
            raf.seek(4);
            raf.write(intToByteArray((int) fileSize), 0, 4);

            // Update data chunk size (file size - 44)
            long dataSize = wavFile.length() - 44;
            raf.seek(40);
            raf.write(intToByteArray((int) dataSize), 0, 4);

            Log.d(TAG, "WAV header updated: fileSize=" + fileSize + ", dataSize=" + dataSize);

        } catch (IOException e) {
            Log.e(TAG, "Failed to update WAV header", e);
        }
    }

    /**
     * Convert int to little-endian byte array
     */
    private byte[] intToByteArray(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    /**
     * Get the buffer size being used
     */
    public int getBufferSize() {
        return bufferSize;
    }

    /**
     * Get the output file
     */
    public File getOutputFile() {
        return outputFile;
    }

    /**
     * Check if AudioRecord is available for the given configuration
     */
    public static boolean isAudioRecordAvailable(int sampleRate, int channels) {
        int channelConfig = (channels == 1) ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            return false;
        }

        AudioRecord testRecord = null;
        try {
            testRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            );

            return testRecord.getState() == AudioRecord.STATE_INITIALIZED;

        } catch (Exception e) {
            Log.w(TAG, "AudioRecord availability check failed", e);
            return false;
        } finally {
            if (testRecord != null) {
                try {
                    testRecord.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing test AudioRecord", e);
                }
            }
        }
    }
}
