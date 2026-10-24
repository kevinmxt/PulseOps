package dev.pulseops.heartbeat;

public class HeartbeatRateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public HeartbeatRateLimitedException(long retryAfterSeconds) {
        super("too many heartbeat reports");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
