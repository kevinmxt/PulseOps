package dev.pulseops.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class LoginRateLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED_KEYS = 10_000;

    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastCleanup = new AtomicReference<>(Instant.EPOCH);

    public LoginRateLimiter(Clock clock) {
        this.clock = clock;
    }

    public static String keyFor(String rawUsername, String remoteAddress) {
        String username;
        try {
            username = UsernameRules.normalize(rawUsername);
        } catch (IllegalArgumentException ex) {
            username = rawUsername == null ? "" : rawUsername.strip();
            if (username.length() > UsernameRules.MAX_LENGTH) {
                username = username.substring(0, UsernameRules.MAX_LENGTH);
            }
        }
        return username + "|" + (remoteAddress == null ? "" : remoteAddress);
    }

    public void checkAllowed(String key) {
        Instant now = clock.instant();
        Window window = windows.get(key);
        if (window == null) {
            return;
        }
        if (!now.isBefore(window.startedAt().plus(WINDOW))) {
            windows.remove(key, window);
            return;
        }
        if (window.failures() >= MAX_FAILURES) {
            throw new LoginRateLimitedException(retryAfterSeconds(now, window.startedAt()));
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_KEYS) {
            purgeExpired(now);
            if (windows.size() >= MAX_TRACKED_KEYS) {
                windows.keySet().stream().findFirst().ifPresent(windows::remove);
            }
        }

        windows.compute(key, (ignored, current) ->
                current == null || !now.isBefore(current.startedAt().plus(WINDOW))
                        ? new Window(now, 1)
                        : new Window(current.startedAt(), current.failures() + 1));
        cleanupExpired(now);
    }

    public void clear(String key) {
        windows.remove(key);
    }

    private void cleanupExpired(Instant now) {
        Instant last = lastCleanup.get();
        if (Duration.between(last, now).compareTo(WINDOW) < 0 || !lastCleanup.compareAndSet(last, now)) {
            return;
        }
        purgeExpired(now);
    }

    private void purgeExpired(Instant now) {
        windows.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().startedAt().plus(WINDOW)));
    }

    private static long retryAfterSeconds(Instant now, Instant windowStart) {
        long millis = Math.max(0, Duration.between(now, windowStart.plus(WINDOW)).toMillis());
        return Math.max(1, Math.min(WINDOW.toSeconds(), (millis + 999) / 1000));
    }

    private record Window(Instant startedAt, int failures) {
    }
}
