package com.capacitor.audioengine;

import android.media.MediaExtractor;
import android.media.MediaMuxer;
import android.util.Log;

/**
 * Utility class for managing audio resources with proper cleanup patterns
 */
public class ResourceManager {
    private static final String TAG = "ResourceManager";

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
}
