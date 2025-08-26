package com.capacitor.audioengine;

import android.text.TextUtils;
import java.io.File;
import java.net.URL;
import java.util.regex.Pattern;

/**
 * Utility class for validating inputs to prevent security issues and ensure data integrity
 */
public class ValidationUtils {
    private static final String TAG = "ValidationUtils";

    // Regex patterns for validation
    private static final Pattern VALID_FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(".*(\\.\\.|~|\\\\|//).*");

    // File size limits (in bytes)
    private static final long MAX_AUDIO_FILE_SIZE = 500 * 1024 * 1024; // 500MB
    private static final long MIN_AUDIO_FILE_SIZE = 100; // 100 bytes

    // Audio parameter limits
    private static final int MIN_SAMPLE_RATE = 8000;
    private static final int MAX_SAMPLE_RATE = 192000;
    private static final int MIN_BITRATE = 32000;
    private static final int MAX_BITRATE = 512000;
    private static final int MIN_CHANNELS = 1;
    private static final int MAX_CHANNELS = 8;
    private static final double MIN_DURATION = 0.1; // 100ms
    private static final double MAX_DURATION = 24 * 3600; // 24 hours

    /**
     * Validate URI is not null or empty
     */
    public static void validateUri(String uri) throws IllegalArgumentException {
        if (TextUtils.isEmpty(uri)) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getMessage());
        }

        if (uri.trim().isEmpty()) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getMessage());
        }
    }

    /**
     * Validate and sanitize file path to prevent path traversal attacks
     */
    public static void validateFilePath(String path) throws SecurityException {
        if (TextUtils.isEmpty(path)) {
            throw new SecurityException(AudioEngineError.INVALID_FILE_PATH.getMessage());
        }

        // Check for path traversal attempts
        if (PATH_TRAVERSAL_PATTERN.matcher(path).matches()) {
            throw new SecurityException(AudioEngineError.INVALID_FILE_PATH.getDetailedMessage("Path traversal attempt detected"));
        }

        // Additional security checks
        String normalizedPath = path.toLowerCase();
        if (normalizedPath.contains("../") || normalizedPath.contains("..\\") ||
            normalizedPath.contains("~/") || normalizedPath.startsWith("/system") ||
            normalizedPath.startsWith("/proc") || normalizedPath.startsWith("/dev")) {
            throw new SecurityException(AudioEngineError.INVALID_FILE_PATH.getDetailedMessage("Access to restricted path"));
        }
    }

    /**
     * Validate time range for audio operations
     */
    public static void validateTimeRange(double startTime, double endTime) throws IllegalArgumentException {
        if (startTime < 0) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_TIME_RANGE.getDetailedMessage("Start time cannot be negative"));
        }

        if (endTime < 0) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_TIME_RANGE.getDetailedMessage("End time cannot be negative"));
        }

        if (endTime <= startTime) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_TIME_RANGE.getDetailedMessage("End time must be greater than start time"));
        }

        double duration = endTime - startTime;
        if (duration < MIN_DURATION) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_TIME_RANGE.getDetailedMessage("Duration too short (minimum " + MIN_DURATION + " seconds)"));
        }

        if (duration > MAX_DURATION) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_TIME_RANGE.getDetailedMessage("Duration too long (maximum " + MAX_DURATION + " seconds)"));
        }
    }

    /**
     * Validate audio recording parameters
     */
    public static void validateAudioParameters(int sampleRate, int channels, int bitrate) throws IllegalArgumentException {
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_AUDIO_PARAMETERS.getDetailedMessage(
                "Sample rate must be between " + MIN_SAMPLE_RATE + " and " + MAX_SAMPLE_RATE + " Hz"));
        }

        if (channels < MIN_CHANNELS || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_AUDIO_PARAMETERS.getDetailedMessage(
                "Channels must be between " + MIN_CHANNELS + " and " + MAX_CHANNELS));
        }

        if (bitrate < MIN_BITRATE || bitrate > MAX_BITRATE) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_AUDIO_PARAMETERS.getDetailedMessage(
                "Bitrate must be between " + MIN_BITRATE + " and " + MAX_BITRATE + " bps"));
        }
    }



    /**
     * Validate file exists and is readable
     */
    public static void validateFileExists(String filePath) throws IllegalArgumentException {
        validateFilePath(filePath);

        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException(AudioEngineError.FILE_NOT_FOUND.getDetailedMessage("File does not exist: " + filePath));
        }

        if (!file.canRead()) {
            throw new IllegalArgumentException(AudioEngineError.FILE_NOT_FOUND.getDetailedMessage("Cannot read file: " + filePath));
        }

        long fileSize = file.length();
        if (fileSize < MIN_AUDIO_FILE_SIZE) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_PARAMETERS.getDetailedMessage("File too small: " + fileSize + " bytes"));
        }

        if (fileSize > MAX_AUDIO_FILE_SIZE) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_PARAMETERS.getDetailedMessage("File too large: " + fileSize + " bytes"));
        }
    }

    /**
     * Validate remote URL
     */
    public static void validateRemoteUrl(String url) throws IllegalArgumentException {
        validateUri(url);

        try {
            URL urlObj = new URL(url);
            String protocol = urlObj.getProtocol().toLowerCase();

            if (!protocol.equals("http") && !protocol.equals("https")) {
                throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getDetailedMessage("Only HTTP/HTTPS URLs are supported"));
            }

            String host = urlObj.getHost();
            if (TextUtils.isEmpty(host)) {
                throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getDetailedMessage("Invalid host in URL"));
            }

            // Prevent access to localhost/private IPs (basic check)
            if (host.equals("localhost") || host.equals("127.0.0.1") || host.startsWith("192.168.") ||
                host.startsWith("10.") || host.startsWith("172.")) {
                throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getDetailedMessage("Access to private/local URLs not allowed"));
            }

        } catch (Exception e) {
            throw new IllegalArgumentException(AudioEngineError.INVALID_URI.getDetailedMessage("Malformed URL: " + e.getMessage()));
        }
    }

    /**
     * Validate filename for security
     */
    public static void validateFilename(String filename) throws SecurityException {
        if (TextUtils.isEmpty(filename)) {
            throw new SecurityException(AudioEngineError.INVALID_PARAMETERS.getDetailedMessage("Filename cannot be empty"));
        }

        if (!VALID_FILENAME_PATTERN.matcher(filename).matches()) {
            throw new SecurityException(AudioEngineError.INVALID_PARAMETERS.getDetailedMessage("Filename contains invalid characters"));
        }

        if (filename.length() > 255) {
            throw new SecurityException(AudioEngineError.INVALID_PARAMETERS.getDetailedMessage("Filename too long"));
        }
    }

    /**
     * Check if URL is a remote URL (HTTP/HTTPS)
     */
    public static boolean isRemoteUrl(String uri) {
        return uri != null && (uri.startsWith("http://") || uri.startsWith("https://"));
    }

    /**
     * Sanitize filename by removing invalid characters
     */
    public static String sanitizeFilename(String filename) {
        if (TextUtils.isEmpty(filename)) {
            return "audio_file";
        }

        // Replace invalid characters with underscores
        String sanitized = filename.replaceAll("[^a-zA-Z0-9._-]", "_");

        // Ensure it's not too long
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 255);
        }

        // Ensure it's not empty after sanitization
        if (sanitized.isEmpty()) {
            sanitized = "audio_file";
        }

        return sanitized;
    }
}
