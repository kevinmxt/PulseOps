package dev.pulseops.heartbeat;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class HeartbeatRateLimiter {

    static final int MAX_REQUESTS_PER_WINDOW = 120;
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastCleanup = new AtomicReference<>(Instant.EPOCH);

    public HeartbeatRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public void acquire(String key) {
        Instant now = clock.instant();
        Window window = windows.compute(key, (ignored, current) ->
                current == null || !now.isBefore(current.startedAt().plus(WINDOW))
                        ? new Window(now, 1)
                        : new Window(current.startedAt(), current.count() + 1));
        if (window.count() > MAX_REQUESTS_PER_WINDOW) {
            throw new HeartbeatRateLimitedException(retryAfterSeconds(now, window.startedAt()));
        }
        cleanupExpired(now);
    }

    private void cleanupExpired(Instant now) {
        Instant last = lastCleanup.get();
        if (Duration.between(last, now).compareTo(WINDOW) < 0 || !lastCleanup.compareAndSet(last, now)) {
            return;
        }
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(WINDOW)));
    }

    private static long retryAfterSeconds(Instant now, Instant windowStart) {
        long millis = Math.max(0, Duration.between(now, windowStart.plus(WINDOW)).toMillis());
        return Math.max(1, Math.min(WINDOW.toSeconds(), (millis + 999) / 1000));
    }

    private record Window(Instant startedAt, int count) {
    }
}
