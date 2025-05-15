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
            call.resolve();
        } catch (IOException e) {
            call.reject("Failed to start recording: " + e.getMessage());
        }
    }

    @PluginMethod
    public void pauseRecording(PluginCall call) {
        if (mediaRecorder != null && isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.pause();
                call.resolve();
            } else {
                call.reject("Pause recording is not supported on this Android version");
            }
        } else {
            call.reject("No active recording");
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
        ret.put("isRecording", isRecording);
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
            // Convert web URL to content URI if needed
            String contentPath = sourcePath;
            if (sourcePath.contains("_capacitor_content_")) {
                // Extract the content URI part
                String[] parts = sourcePath.split("_capacitor_content_/");
                if (parts.length > 1) {
                    contentPath = "content://" + parts[1];
                }
            }

            // Parse the content URI
            Uri sourceUri = Uri.parse(contentPath);

            // Create output directory
            File audioDir = new File(getContext().getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Trimmed");
            if (!audioDir.exists()) {
                audioDir.mkdirs();
            }

            String outputPath = new File(audioDir, "trimmed_" + System.currentTimeMillis() + ".mp3").getAbsolutePath();

            // Initialize extractor
            MediaExtractor extractor = new MediaExtractor();

            try {
                if (sourceUri.getScheme() != null && (sourceUri.getScheme().equals("content") || sourceUri.getScheme().equals("file"))) {
                    // For content:// and file:// URIs
                    extractor.setDataSource(getContext(), sourceUri, null);
                } else {
                    // For direct file paths
                    String realPath = contentPath;
                    if (contentPath.startsWith("file://")) {
                        realPath = contentPath.substring(7);
                    }
                    File sourceFile = new File(realPath);
                    if (!sourceFile.exists()) {
                        call.reject("Source file does not exist: " + realPath);
                        return;
                    }
                    if (!sourceFile.canRead()) {
                        call.reject("Cannot read source file: " + realPath);
                        return;
                    }
                    extractor.setDataSource(sourceFile.getAbsolutePath());
                }
            } catch (Exception e) {
                call.reject("Failed to access audio file: " + e.getMessage() + " (Path: " + contentPath + ")");
                return;
            }

            // Find audio track
            int audioTrackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                extractor.release();
                call.reject("No audio track found");
                return;
            }

            // Get audio format details
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            String mime = format.getString(MediaFormat.KEY_MIME);

            // Create decoder
            MediaCodec decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // Create encoder with same parameters
            MediaFormat encFormat = MediaFormat.createAudioFormat(mime, sampleRate, channelCount);
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, 2); // AAC LC
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec encoder = MediaCodec.createEncoderByType(mime);
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // Setup muxer
            MediaMuxer muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerTrackIndex = -1;

            // Convert time to microseconds
            long startUs = (long)(startTime * 1_000_000);
            long endUs = (long)(endTime * 1_000_000);

            // Select track and seek to start position
            extractor.selectTrack(audioTrackIndex);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            // Initialize buffers
            ByteBuffer[] decoderInputBuffers = decoder.getInputBuffers();
            ByteBuffer[] decoderOutputBuffers = decoder.getOutputBuffers();
            ByteBuffer[] encoderInputBuffers = encoder.getInputBuffers();
            ByteBuffer[] encoderOutputBuffers = encoder.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo outputInfo = new MediaCodec.BufferInfo();
            boolean decoderDone = false;
            boolean encoderDone = false;
            boolean inputDone = false;

            // Start processing loop
            while (!encoderDone) {
                // Feed decoder input
                if (!inputDone) {
                    int inputBufIndex = decoder.dequeueInputBuffer(10000);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = decoderInputBuffers[inputBufIndex];
                        inputBuf.clear();
                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        long presentationTime = extractor.getSampleTime();

                        if (sampleSize < 0 || presentationTime >= endUs) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTime, 0);
                            extractor.advance();
                        }
                    }
                }

                // Get decoded data
                if (!decoderDone) {
                    int decoderStatus = decoder.dequeueOutputBuffer(info, 10000);
                    if (decoderStatus >= 0) {
                        ByteBuffer decodedData = decoderOutputBuffers[decoderStatus];
                        decodedData.position(info.offset);
                        decodedData.limit(info.offset + info.size);

                        // Feed encoder input
                        int encoderStatus = encoder.dequeueInputBuffer(10000);
                        if (encoderStatus >= 0) {
                            ByteBuffer encoderBuffer = encoderInputBuffers[encoderStatus];
                            encoderBuffer.clear();
                            if (info.size > 0) {
                                encoderBuffer.put(decodedData);
                            }

                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoder.queueInputBuffer(encoderStatus, 0, 0, info.presentationTimeUs - startUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decoderDone = true;
                            } else {
                                encoder.queueInputBuffer(encoderStatus, 0, info.size, info.presentationTimeUs - startUs, 0);
                            }
                        }

                        decoder.releaseOutputBuffer(decoderStatus, false);
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                        decoderOutputBuffers = decoder.getOutputBuffers();
                    } else if (decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // Ignore decoder format changes
                    }
                }

                // Get encoded data
                int encoderStatus = encoder.dequeueOutputBuffer(outputInfo, 10000);
                if (encoderStatus >= 0) {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    encodedData.position(outputInfo.offset);
                    encodedData.limit(outputInfo.offset + outputInfo.size);

                    if (outputInfo.size > 0 && (outputInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (muxerTrackIndex == -1) {
                            MediaFormat newFormat = encoder.getOutputFormat();
                            muxerTrackIndex = muxer.addTrack(newFormat);
                            muxer.start();
                        }

                        muxer.writeSampleData(muxerTrackIndex, encodedData, outputInfo);
                    }

                    encoder.releaseOutputBuffer(encoderStatus, false);

                    if ((outputInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        encoderDone = true;
                    }
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    encoderOutputBuffers = encoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (muxerTrackIndex >= 0) {
                        throw new RuntimeException("Format changed twice!");
                    }
                    MediaFormat newFormat = encoder.getOutputFormat();
                    muxerTrackIndex = muxer.addTrack(newFormat);
                    muxer.start();
                }
            }

            // Cleanup
            extractor.release();
            decoder.stop();
            decoder.release();
            encoder.stop();
            encoder.release();
            muxer.stop();
            muxer.release();

            // Create content URI for the trimmed file
            File trimmedFile = new File(outputPath);
            Uri contentUri = FileProvider.getUriForFile(
                getContext(),
                getContext().getPackageName() + ".fileprovider",
                trimmedFile
            );

            // Get metadata for the trimmed file
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(outputPath);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            retriever.release();

            // Create response object
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
        if (isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.pause();
            }
            wasRecordingBeforeInterruption = true;
        }
    }

    private void handleInterruptionEnded(boolean shouldResume) {
        if (wasRecordingBeforeInterruption && shouldResume) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.resume();
            }
        }
        wasRecordingBeforeInterruption = false;
    }

    @Override
    protected void handleOnPause() {
        if (isRecording) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.pause();
            }
            wasRecordingBeforeInterruption = true;
        }
        notifyListeners("recordingInterruption", new JSObject().put("message", "App moved to background"));
    }

    @Override
    protected void handleOnResume() {
        if (wasRecordingBeforeInterruption) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                mediaRecorder.resume();
            }
        }
        notifyListeners("recordingInterruption", new JSObject().put("message", "App became active"));
    }

    @Override
    protected void handleOnDestroy() {
        cleanupInterruptionListeners();
        if (mediaRecorder != null) {
            mediaRecorder.release();
            mediaRecorder = null;
        }
        isRecording = false;
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
