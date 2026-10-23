package dev.pulseops.notification;

public class NotificationSendException extends RuntimeException {

    private final boolean retryable;

    public NotificationSendException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public NotificationSendException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
