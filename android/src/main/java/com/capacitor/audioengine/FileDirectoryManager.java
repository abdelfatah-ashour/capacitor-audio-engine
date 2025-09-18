package com.capacitor.audioengine;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/**
 * Manages file directories for audio recordings and processing
 */
public class FileDirectoryManager {
    private static final String TAG = "FileDirectoryManager";

    private final Context context;

    public FileDirectoryManager(Context context) {
        this.context = context;
    }

    /**
     * Get recordings directory, creating it if it doesn't exist
     */
    public File getRecordingsDirectory() throws IOException {
        File recordingsDir = new File(context.getExternalFilesDir(null), "Recordings");
        if (!recordingsDir.exists()) {
            if (!recordingsDir.mkdirs()) {
                throw new IOException("Failed to create recordings directory");
            }
        }
        Log.d(TAG, "Recordings directory: " + recordingsDir.getAbsolutePath());
        return recordingsDir;
    }

    /**
     * Get processed audio directory, creating it if it doesn't exist
     */
    public File getProcessedDirectory() throws IOException {
        File processedDir = new File(context.getExternalFilesDir(null), "Processed");
        if (!processedDir.exists()) {
            if (!processedDir.mkdirs()) {
                throw new IOException("Failed to create processed audio directory");
            }
        }
        Log.d(TAG, "Processed directory: " + processedDir.getAbsolutePath());
        return processedDir;
    }

    /**
     * Get cache directory for temporary files
     */
    public File getCacheDirectory() throws IOException {
        File cacheDir = new File(context.getCacheDir(), "AudioEngine");
        if (!cacheDir.exists()) {
            if (!cacheDir.mkdirs()) {
                throw new IOException("Failed to create cache directory");
            }
        }
        Log.d(TAG, "Cache directory: " + cacheDir.getAbsolutePath());
        return cacheDir;
    }

    /**
     * Create a processed file with timestamp
     */
    public File createProcessedFile(String prefix) throws IOException {
        File processedDir = getProcessedDirectory();
        long timestamp = System.currentTimeMillis();
        String filename = (prefix != null ? prefix : "processed") + "_" + timestamp + ".m4a";
        return new File(processedDir, filename);
    }

    /**
     * Clean up old files in a directory
     */
    public void cleanupOldFiles(File directory, long olderThanMillis) {
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        long cutoffTime = System.currentTimeMillis() - olderThanMillis;
        File[] files = directory.listFiles();

        if (files != null) {
            int deletedCount = 0;
            for (File file : files) {
                if (file.isFile() && file.lastModified() < cutoffTime) {
                    if (file.delete()) {
                        deletedCount++;
                        Log.d(TAG, "Deleted old file: " + file.getName());
                    } else {
                        Log.w(TAG, "Failed to delete old file: " + file.getName());
                    }
                }
            }
            Log.d(TAG, "Cleaned up " + deletedCount + " old files from " + directory.getName());
        }
    }

    /**
     * Get available storage space in bytes
     */
    public long getAvailableSpace() {
        try {
            File recordingsDir = getRecordingsDirectory();
            return recordingsDir.getUsableSpace();
        } catch (IOException e) {
            Log.e(TAG, "Failed to get available space", e);
            return 0;
        }
    }
}
