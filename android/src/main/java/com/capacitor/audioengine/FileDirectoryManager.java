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
     * Create a processed file with timestamp
     */
    public File createProcessedFile(String prefix) throws IOException {
        File processedDir = getProcessedDirectory();
        long timestamp = System.currentTimeMillis();
        String filename = (prefix != null ? prefix : "processed") + "_" + timestamp + ".m4a";
        return new File(processedDir, filename);
    }
}
