package com.capacitor.audioengine;

import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Utility class for managing audio resources with proper cleanup patterns
 */
public class ResourceManager {
    private static final String TAG = "ResourceManager";

    /**
     * Safely release MediaRecorder with proper error handling
     */
    public static void releaseMediaRecorder(MediaRecorder mediaRecorder) {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaRecorder was not recording when stop() was called", e);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaRecorder", e);
            }

            try {
                mediaRecorder.reset();
            } catch (Exception e) {
                Log.e(TAG, "Error resetting MediaRecorder", e);
            }

            try {
                mediaRecorder.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaRecorder", e);
            }
        }
    }

    /**
     * Safely release MediaPlayer with proper error handling
     */
    public static void releaseMediaPlayer(MediaPlayer mediaPlayer) {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaPlayer was not playing when stop() was called", e);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaPlayer", e);
            }

            try {
                mediaPlayer.reset();
            } catch (Exception e) {
                Log.e(TAG, "Error resetting MediaPlayer", e);
            }

            try {
                mediaPlayer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaPlayer", e);
            }
        }
    }

    /**
     * Safely release MediaExtractor with proper error handling
     */
    public static void releaseMediaExtractor(MediaExtractor extractor) {
        if (extractor != null) {
            try {
                extractor.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaExtractor", e);
            }
        }
    }

    /**
     * Safely release MediaMuxer with proper error handling
     */
    public static void releaseMediaMuxer(MediaMuxer muxer) {
        if (muxer != null) {
            try {
                muxer.stop();
            } catch (IllegalStateException e) {
                Log.w(TAG, "MediaMuxer was not started when stop() was called", e);
            } catch (Exception e) {
                Log.e(TAG, "Error stopping MediaMuxer", e);
            }

            try {
                muxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMuxer", e);
            }
        }
    }

    /**
     * Safely release MediaMetadataRetriever with proper error handling
     */
    public static void releaseMediaMetadataRetriever(MediaMetadataRetriever retriever) {
        if (retriever != null) {
            try {
                retriever.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing MediaMetadataRetriever", e);
            }
        }
    }

    /**
     * Auto-closeable wrapper for MediaExtractor
     */
    public static class SafeMediaExtractor implements AutoCloseable {
        private final MediaExtractor extractor;

        public SafeMediaExtractor() {
            this.extractor = new MediaExtractor();
        }

        public MediaExtractor getExtractor() {
            return extractor;
        }

        @Override
        public void close() {
            releaseMediaExtractor(extractor);
        }
    }

    /**
     * Auto-closeable wrapper for MediaMuxer
     */
    public static class SafeMediaMuxer implements AutoCloseable {
        private final MediaMuxer muxer;

        public SafeMediaMuxer(String outputPath, int format) throws Exception {
            this.muxer = new MediaMuxer(outputPath, format);
        }

        public MediaMuxer getMuxer() {
            return muxer;
        }

        @Override
        public void close() {
            releaseMediaMuxer(muxer);
        }
    }

    /**
     * Auto-closeable wrapper for MediaMetadataRetriever
     */
    public static class SafeMediaMetadataRetriever implements AutoCloseable {
        private final MediaMetadataRetriever retriever;

        public SafeMediaMetadataRetriever() {
            this.retriever = new MediaMetadataRetriever();
        }

        public MediaMetadataRetriever getRetriever() {
            return retriever;
        }

        @Override
        public void close() {
            releaseMediaMetadataRetriever(retriever);
        }
    }
}
