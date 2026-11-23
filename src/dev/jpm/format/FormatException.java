package dev.jpm.format;

/**
 * A problem the user can fix, such as a bad config; printed without a stack trace.
 */
public final class FormatException extends RuntimeException {
    public FormatException(String message) {
        super(message);
    }

    public FormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
