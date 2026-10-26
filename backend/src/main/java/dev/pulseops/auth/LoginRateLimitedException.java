package dev.pulseops.auth;

public class LoginRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;
    private final boolean capacityExhausted;

    public LoginRateLimitedException(long retryAfterSeconds) {
        this(retryAfterSeconds, false);
    }

    public LoginRateLimitedException(long retryAfterSeconds, boolean capacityExhausted) {
        super(capacityExhausted ? "too many login attempts" : "too many failed login attempts");
        this.retryAfterSeconds = retryAfterSeconds;
        this.capacityExhausted = capacityExhausted;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isCapacityExhausted() {
        return capacityExhausted;
    }
}
