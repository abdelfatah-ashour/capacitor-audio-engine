package com.capacitor.audioengine;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.media.MediaMetadataRetriever;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaCodec;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

@CapacitorPlugin(
    name = "CapacitorAudioEngine",
    permissions = {
        @Permission(
            alias = "microphone",
            strings = { Manifest.permission.RECORD_AUDIO }
        )
    }
)
public class CapacitorAudioEnginePlugin extends Plugin {
    private MediaRecorder mediaRecorder;
    private String audioFilePath;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean hasListeners = false;
    private boolean wasRecordingBeforeInterruption = false;
    private AudioManager audioManager;
    private BroadcastReceiver audioFocusChangeReceiver;
    private BroadcastReceiver phoneStateReceiver;

    @PluginMethod
    public void checkPermission(PluginCall call) {
        boolean hasPermission = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        JSObject ret = new JSObject();
        ret.put("granted", hasPermission);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionForAlias("microphone", call, "permissionCallback");
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
        }
    }

    @PluginMethod
    public void startRecording(PluginCall call) {
        if (mediaRecorder != null) {
            call.reject("Recording is already in progress");
            return;
        }

        try {
            File audioDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Recordings");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            audioFilePath = new File(audioDir, "recording_" + System.currentTimeMillis() + ".mp3").getAbsolutePath();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(getContext());
            } else {
                mediaRecorder = new MediaRecorder();
            }

            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioChannels(1); // Mono recording
            mediaRecorder.setAudioEncodingBitRate(128000);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setOutputFile(audioFilePath);

            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            isPaused = false;
            call.resolve();
        } catch (IOException e) {
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder != null && isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (isPaused) {
                    call.reject("Recording is already paused.");
                    return;
                }
                mediaRecorder.pause();
                isPaused = true;
                call.resolve();
            } else {
                call.reject("Pause recording is not supported on this Android version (requires API 24+).");
            }
        } else {
            call.reject("No active recording session to pause.");
        }
    }

    @PluginMethod
    public void resumeRecording(PluginCall call) {
        if (mediaRecorder != null && isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (!isPaused) {
                    call.reject("Recording is not paused.");
                    return;
                }
                mediaRecorder.resume();
                isPaused = false;
                call.resolve();
            } else {
                call.reject("Resume recording is not supported on this Android version (requires API 24+).");
            }
        } else {
            call.reject("No active recording session to resume.");
        }
    }

    @PluginMethod
    public void stopRecording(PluginCall call) {
        if (mediaRecorder != null && isRecording) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
                isRecording = false;
                isPaused = false;

                // Get the recorded file and validate
                File audioFile = new File(audioFilePath);
                if (!audioFile.exists()) {
                    call.reject("Recording file not found");
                    return;
                }

                long fileSize = audioFile.length();
                if (fileSize == 0) {
                    call.reject("Recording file is empty");
                    return;
                }

                // Get recording metadata
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                retriever.setDataSource(audioFilePath);
                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
                retriever.release();

                // Calculate duration in seconds
                double durationSeconds = 0;
                if (durationStr != null) {
                    durationSeconds = Double.parseDouble(durationStr) / 1000.0;
                }

                // Create content URI using FileProvider
                Uri contentUri = FileProvider.getUriForFile(
                    getContext(),
                    getContext().getPackageName() + ".fileprovider",
                    audioFile
                );

                // Create response object
                JSObject ret = new JSObject();
                ret.put("path", audioFile.getAbsolutePath());
                ret.put("uri", contentUri.toString());
                ret.put("mimeType", "audio/mpeg");
                ret.put("size", fileSize);
                ret.put("duration", durationSeconds);
                ret.put("sampleRate", 44100);
                ret.put("channels", 1);
                ret.put("bitrate", bitrate != null ? Integer.parseInt(bitrate) : 128000);
                ret.put("createdAt", System.currentTimeMillis());
                ret.put("filename", audioFile.getName());

                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Failed to stop recording: " + e.getMessage());
            }
        } else {
            call.reject("No active recording");
        }
    }

    @PluginMethod
    public void getDuration(PluginCall call) {
        if (mediaRecorder != null && isRecording) {
            JSObject ret = new JSObject();
            ret.put("duration", mediaRecorder.getMaxAmplitude() / 1000.0); // Convert to seconds
            call.resolve(ret);
        } else {
            call.reject("No active recording");
        }
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        String currentStatus;
        if (!isRecording) {
            currentStatus = "idle";
        } else {
            if (isPaused) {
                currentStatus = "paused";
            } else {
                currentStatus = "recording";
            }
        }
        ret.put("status", currentStatus);
        ret.put("isRecording", isRecording && (currentStatus.equals("recording") || currentStatus.equals("paused")));
        call.resolve(ret);
    }

    @PluginMethod
    public void trimAudio(PluginCall call) {
        String sourcePath = call.getString("path");
        double startTime = call.getDouble("startTime", 0.0);
        double endTime = call.getDouble("endTime", 0.0);

        if (sourcePath == null) {
            call.reject("Source path is required");
            return;
        }

        if (endTime <= startTime) {
            call.reject("End time must be greater than start time");
            return;
        }

        try {
            Uri sourceUri;
            File sourceFile;

            if (sourcePath.startsWith("capacitor://localhost/_capacitor_file_")) {
                String actualPath = sourcePath.substring("capacitor://localhost/_capacitor_file_".length());
                sourceFile = new File(actualPath);
                sourceUri = Uri.fromFile(sourceFile);
            } else if (sourcePath.startsWith("file://")) {
                sourceUri = Uri.parse(sourcePath);
                sourceFile = new File(sourceUri.getPath());
            } else if (sourcePath.contains("_capacitor_content_")) {
                sourceUri = Uri.parse(sourcePath);
                sourceFile = null;
            } else {
                sourceFile = new File(sourcePath);
                sourceUri = Uri.fromFile(sourceFile);
            }

            if (sourceFile != null && !sourceFile.exists()) {
                call.reject("Source audio file does not exist at path: " + sourceFile.getAbsolutePath());
                return;
            }

            // Create output directory
            File audioDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Trimmed");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            String outputFileName = "trimmed_" + System.currentTimeMillis() + ".mp3";
            File outputFile = new File(audioDir, outputFileName);

            // Remove output file if it already exists
            if (outputFile.exists()) {
                outputFile.delete();
            }
            String outputPath = outputFile.getAbsolutePath();

            // Initialize extractor
            MediaExtractor extractor = new MediaExtractor();
            if (sourceFile != null) {
                extractor.setDataSource(sourceFile.getAbsolutePath());
            } else {
                ParcelFileDescriptor pfd = getContext().getContentResolver().openFileDescriptor(sourceUri, "r");
                if (pfd == null) {
                    call.reject("Failed to open source audio file from URI.");
                    return;
                }
                extractor.setDataSource(pfd.getFileDescriptor());
            }

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat trackFormat = extractor.getTrackFormat(i);
                String mime = trackFormat.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    format = trackFormat;
                    break;
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                extractor.release();
                call.reject("No audio track found in the source file");
                return;
            }

            // Check duration from metadata before proceeding
            long durationUs = format.getLong(MediaFormat.KEY_DURATION, 0);
            if (durationUs <= 0) {
                MediaMetadataRetriever tempRetriever = new MediaMetadataRetriever();
                if (sourceFile != null) {
                    tempRetriever.setDataSource(sourceFile.getAbsolutePath());
                } else {
                    ParcelFileDescriptor pfdForRetriever = getContext().getContentResolver().openFileDescriptor(sourceUri, "r");
                    if (pfdForRetriever != null) {
                        tempRetriever.setDataSource(pfdForRetriever.getFileDescriptor());
                    }
                }
                String durationStrMeta = tempRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                tempRetriever.release();
                if (durationStrMeta != null) {
                    durationUs = Long.parseLong(durationStrMeta) * 1000;
                }
            }

            if (durationUs <= 0) {
                extractor.release();
                call.reject("Source audio file has invalid or zero duration.");
                return;
            }
            double sourceDurationSeconds = durationUs / 1_000_000.0;
            if (startTime >= sourceDurationSeconds) {
                extractor.release();
                call.reject("Start time is beyond the source audio duration.");
                return;
            }
            if (endTime > sourceDurationSeconds) {
                endTime = sourceDurationSeconds;
            }
            if (endTime <= startTime) {
                extractor.release();
                call.reject("End time must be greater than start time after adjusting to source duration.");
                return;
            }

            // Get audio format details
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = format.getString(MediaFormat.KEY_MIME);

            MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            MediaFormat encFormat = MediaFormat.createAudioFormat(mime, sampleRate, channelCount);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2);
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec encoder = MediaCodec.createEncoderByType(mime);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrackIndex = -1;

            long startUs = (long)(startTime * 1_000_000);
            long endUs = (long)(endTime * 1_000_000);

            extractor.selectTrack(audioTrackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
            ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
            ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
            ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean inputDone = false;

            while (!encoderDone) {
                if (!decoderDone && !inputDone) {
                    int inputBufIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer dstBuf = decoderInputBuffers[inputBufIndex];
                        int sampleSize = extractor.readSampleData(dstBuf, 0);
                        long presentationTimeUs = extractor.getSampleTime();

                        if (sampleSize < 0 || presentationTimeUs >= endUs) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            decoderDone = true;
                            inputDone = true;
                        } else if (presentationTimeUs >= startUs) {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, extractor.getSampleFlags());
                        }
                        extractor.advance();
                    }
                }

                boolean encoderOutputAvailable = true;
                boolean decoderOutputAvailable = true;

                while (encoderOutputAvailable || decoderOutputAvailable) {
                    int encoderStatus = encoder.dequeueOutputBuffer(outputInfo, 10000);
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        encoderOutputAvailable = false;
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        encoderOutputBuffers = encoder.getOutputBuffers();
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerTrackIndex != -1) {
                            throw new RuntimeException("Muxer track already added, but encoder format changed again");
                        }
                        MediaFormat newFormat = encoder.getOutputFormat();
                        muxerTrackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                    } else if (encoderStatus < 0) {
                    } else {
                        ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                        if (encodedData == null) {
                            throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                        }

                        if ((outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            outputInfo.size = 0;
                        }

                        if (outputInfo.size != 0 && muxerTrackIndex != -1) {
                            encodedData.position(outputInfo.offset);
                            encodedData.limit(outputInfo.offset + outputInfo.size);
                            muxer.writeSampleData(muxerTrackIndex, encodedData, outputInfo);
                        }

                        encoder.releaseOutputBuffer(encoderStatus, false);

                        if ((outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true;
                            break;
                        }
                    }

                    if (encoderDone) break;

                    if (!decoderDone) {
                        int decoderStatus = decoder.dequeueOutputBuffer(info, 10000);
                        if (decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            decoderOutputAvailable = false;
                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                            decoderOutputBuffers = decoder.getOutputBuffers();
                        } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        } else if (decoderStatus < 0) {
                        } else {
                            ByteBuffer decodedData = decoderOutputBuffers[decoderStatus];
                            if (decodedData == null) {
                                throw new RuntimeException("decoderOutputBuffer " + decoderStatus + " was null");
                            }

                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                int encInIdx = encoder.dequeueInputBuffer(10000);
                                if (encInIdx >= 0) {
                                    encoder.queueInputBuffer(encInIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                }
                                decoderDone = true;
                            } else if (info.size > 0) {
                                int encInIdx = encoder.dequeueInputBuffer(10000);
                                if (encInIdx >= 0) {
                                    ByteBuffer encInputBuf = encoderInputBuffers[encInIdx];
                                    encInputBuf.clear();
                                    encInputBuf.put(decodedData);
                                    encoder.queueInputBuffer(encInIdx, 0, info.size, info.presentationTimeUs, 0);
                                }
                            }
                            decoder.releaseOutputBuffer(decoderStatus, false);
                        }
                    } else {
                        decoderOutputAvailable = false;
                    }
                }
            }

            extractor.release();
            decoder.stop();
            decoder.release();
            encoder.stop();
            encoder.release();
            muxer.stop();
            muxer.release();

            File trimmedFile = new File(outputPath);
            Uri contentUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                trimmedFile
            );

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(outputPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            retriever.release();

            JSObject ret = new JSObject();
            ret.put("path", outputPath);
            ret.put("uri", contentUri.toString());
            ret.put("webPath", "capacitor://localhost/_capacitor_file_" + outputPath);
            ret.put("mimeType", "audio/mpeg");
            ret.put("size", trimmedFile.length());
            ret.put("duration", durationStr != null ? Double.parseDouble(durationStr) / 1000.0 : 0);
            ret.put("sampleRate", sampleRate);
            ret.put("channels", channelCount);
            ret.put("bitrate", bitrate != null ? Integer.parseInt(bitrate) : 128000);
            ret.put("createdAt", System.currentTimeMillis());
            ret.put("filename", trimmedFile.getName());

            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Failed to trim audio: " + e.getMessage());
        }
    }

    @PluginMethod
    public void startMonitoring(PluginCall call) {
        hasListeners = true;
        setupInterruptionListeners();
        call.resolve();
    }

    @PluginMethod
    public void stopMonitoring(PluginCall call) {
        hasListeners = false;
        cleanupInterruptionListeners();
        call.resolve();
    }

    private void setupInterruptionListeners() {
        // Setup audio focus change listener
        audioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        audioFocusChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                    // Headphones unplugged
                    notifyListeners("recordingInterruption", new JSObject().put("message", "Audio route changed: headphones unplugged"));
                }
            }
        };

        // Register audio focus change receiver
        IntentFilter audioFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        getContext().registerReceiver(audioFocusChangeReceiver, audioFilter);

        // Setup audio focus change listener
        AudioManager.OnAudioFocusChangeListener audioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
            @Override
            public void onAudioFocusChange(int focusChange) {
                switch (focusChange) {
                    case AudioManager.AUDIOFOCUS_LOSS:
                        // Long term loss of audio focus
                        handleInterruptionBegan();
                        notifyListeners("recordingInterruption", new JSObject().put("message", "Interruption began"));
                        break;
                    case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                        // Temporary loss of audio focus
                        handleInterruptionBegan();
                        notifyListeners("recordingInterruption", new JSObject().put("message", "Interruption began"));
                        break;
                    case AudioManager.AUDIOFOCUS_GAIN:
                        // Regained audio focus
                        handleInterruptionEnded(true);
                        notifyListeners("recordingInterruption", new JSObject().put("message", "Interruption ended - should resume"));
                        break;
                }
            }
        };

        // Request audio focus
        int result = audioManager.requestAudioFocus(audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Audio focus granted
        }
    }

    private void cleanupInterruptionListeners() {
        if (audioFocusChangeReceiver != null) {
            try {
                getContext().unregisterReceiver(audioFocusChangeReceiver);
            } catch (Exception e) {
                // Receiver might not be registered
            }
        }
    }

    private void handleInterruptionBegan() {
        if (isRecording && !isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder.pause();
                    isPaused = true;
                } catch (IllegalStateException e) {
                    System.err.println("Error pausing on interruption: " + e.getMessage());
                }
            }
            wasRecordingBeforeInterruption = true;
        }
    }

    private void handleInterruptionEnded(boolean shouldResume) {
        if (wasRecordingBeforeInterruption && shouldResume && isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder.resume();
                    isPaused = false;
                } catch (IllegalStateException e) {
                    System.err.println("Error resuming after interruption: " + e.getMessage());
                }
            }
        }
        wasRecordingBeforeInterruption = false;
    }

    @Override
    protected void handleOnPause() {
        super.handleOnPause();
        if (isRecording && !isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder.pause();
                    isPaused = true;
                } catch (IllegalStateException e) {
                    System.err.println("Error pausing on handleOnPause: " + e.getMessage());
                }
            }
            if (isRecording) wasRecordingBeforeInterruption = true;
        }
        notifyListeners("recordingInterruption", new JSObject().put("message", "App moved to background"));
    }

    @Override
    protected void handleOnResume() {
        super.handleOnResume();
        if (wasRecordingBeforeInterruption && isPaused) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    mediaRecorder.resume();
                    isPaused = false;
                } catch (IllegalStateException e) {
                    System.err.println("Error resuming on handleOnResume: " + e.getMessage());
                }
            }
        }
        if (!isPaused) {
            wasRecordingBeforeInterruption = false;
        }
        notifyListeners("recordingInterruption", new JSObject().put("message", "App became active"));
    }

    @Override
    protected void handleOnDestroy() {
        super.handleOnDestroy();
        cleanupInterruptionListeners();
        if (mediaRecorder != null) {
            try {
                if (isRecording) {
                    mediaRecorder.stop();
                }
                mediaRecorder.release();
            } catch (IllegalStateException e) {
                System.err.println("Error stopping/releasing MediaRecorder in onDestroy: " + e.getMessage());
            }
            mediaRecorder = null;
        }
        isRecording = false;
        isPaused = false;
    }

    @PermissionCallback
    private void permissionCallback(PluginCall call) {
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
        } else {
            JSObject ret = new JSObject();
            ret.put("granted", false);
            call.resolve(ret);
        }
    }
}
