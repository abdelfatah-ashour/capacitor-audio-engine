package com.capacitor.audioengine;

/**
 * Standardized error codes and messages for the Audio Engine plugin
 */
public enum AudioEngineError {
    // Permission errors
    PERMISSION_DENIED("PERMISSION_DENIED", "Required permissions not granted"),
    MICROPHONE_PERMISSION_DENIED("MICROPHONE_PERMISSION_DENIED", "Microphone permission not granted"),
    NOTIFICATION_PERMISSION_DENIED("NOTIFICATION_PERMISSION_DENIED", "Notification permission not granted (required for background recording)"),

    // State errors
    INVALID_STATE("INVALID_STATE", "Operation not allowed in current state"),
    RECORDING_IN_PROGRESS("RECORDING_IN_PROGRESS", "Recording is already in progress"),
    NO_ACTIVE_RECORDING("NO_ACTIVE_RECORDING", "No active recording session"),

    // File errors
    FILE_NOT_FOUND("FILE_NOT_FOUND", "Audio file not found"),
    FILE_CREATION_FAILED("FILE_CREATION_FAILED", "Failed to create audio file"),
    INVALID_FILE_PATH("INVALID_FILE_PATH", "Invalid or unsafe file path"),
    DIRECTORY_CREATION_FAILED("DIRECTORY_CREATION_FAILED", "Failed to create directory"),

    // Network errors
    NETWORK_ERROR("NETWORK_ERROR", "Network connection failed"),
    NETWORK_UNAVAILABLE("NETWORK_UNAVAILABLE", "Network is not available for remote audio"),
    REMOTE_FILE_ERROR("REMOTE_FILE_ERROR", "Failed to access remote audio file"),

    // Resource errors
    RESOURCE_BUSY("RESOURCE_BUSY", "Audio resource is currently busy"),
    MICROPHONE_BUSY("MICROPHONE_BUSY", "Microphone is in use by another application"),
    INSUFFICIENT_STORAGE("INSUFFICIENT_STORAGE", "Insufficient storage space"),

    // Codec/Format errors
    CODEC_ERROR("CODEC_ERROR", "Audio codec error"),
    UNSUPPORTED_FORMAT("UNSUPPORTED_FORMAT", "Unsupported audio format"),
    INVALID_AUDIO_PARAMETERS("INVALID_AUDIO_PARAMETERS", "Invalid audio parameters"),

    // Processing errors
    TRIMMING_FAILED("TRIMMING_FAILED", "Audio trimming operation failed"),
    CONCATENATION_FAILED("CONCATENATION_FAILED", "Audio concatenation failed"),


    // Validation errors
    INVALID_URI("INVALID_URI", "Invalid or empty URI"),
    INVALID_TIME_RANGE("INVALID_TIME_RANGE", "Invalid time range specified"),
    INVALID_PARAMETERS("INVALID_PARAMETERS", "Invalid parameters provided"),

    // Generic errors
    INITIALIZATION_FAILED("INITIALIZATION_FAILED", "Audio engine initialization failed"),
    OPERATION_TIMEOUT("OPERATION_TIMEOUT", "Operation timed out"),
    UNKNOWN_ERROR("UNKNOWN_ERROR", "An unknown error occurred");

    private final String code;
    private final String message;

    AudioEngineError(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getDetailedMessage(String details) {
        return message + (details != null ? ": " + details : "");
    }

    /**
     * Create a standardized error response for plugin calls
     */
    public com.getcapacitor.JSObject toJSObject(String details) {
        com.getcapacitor.JSObject error = new com.getcapacitor.JSObject();
        error.put("code", code);
        error.put("message", getDetailedMessage(details));
        error.put("details", details);
        return error;
    }
}
