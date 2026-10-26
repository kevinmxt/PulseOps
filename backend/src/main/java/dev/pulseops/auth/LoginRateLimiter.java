package dev.pulseops.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class LoginRateLimiter {

    static final int MAX_ATTEMPTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_TRACKED_KEYS = 10_000;
    private static final Duration RECLAIM_INTERVAL = Duration.ofSeconds(1);

    private final Clock clock;
    private final int maxTrackedKeys;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final AtomicReference<Instant> lastCleanup = new AtomicReference<>(Instant.EPOCH);
    private final AtomicReference<Instant> lastReclaim = new AtomicReference<>(Instant.EPOCH);
    private final AtomicLong lastGeneration = new AtomicLong();

    public LoginRateLimiter(Clock clock,
                            @Value("${pulseops.auth.rate-limit.max-keys:10000}") int maxTrackedKeys) {
        this.clock = clock;
        this.maxTrackedKeys = maxTrackedKeys;
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

    public Attempt beginAttempt(String key) {
        Instant now = clock.instant();
        Window window = admitAttempt(key, now);
        cleanupExpired(now);
        return new Attempt(key, window.generation());
    }

    private Window admitAttempt(String key, Instant now) {
        synchronized (admissionLock) {
            if (!windows.containsKey(key)) {
                if (windows.size() >= maxTrackedKeys) {
                    reclaimExpired(now);
                }
                if (windows.size() >= maxTrackedKeys) {
                    throw new LoginRateLimitedException(WINDOW.toSeconds(), true);
                }
            }
            return windows.compute(key, (ignored, current) -> {
                Window active = current;
                if (active == null || !now.isBefore(active.startedAt().plus(WINDOW))) {
                    active = new Window(lastGeneration.incrementAndGet(), now, 0, 0);
                }
                if (active.usedSlots() >= MAX_ATTEMPTS) {
                    throw new LoginRateLimitedException(retryAfterSeconds(now, active.startedAt()));
                }
                return new Window(active.generation(), active.startedAt(), active.failures(),
                        active.inFlight() + 1);
            });
        }
    }

    public void recordFailure(Attempt attempt) {
        Instant now = clock.instant();
        windows.compute(attempt.key, (ignored, current) -> {
            if (current == null || current.generation() != attempt.generation) {
                return current;
            }
            if (!now.isBefore(current.startedAt().plus(WINDOW))) {
                return null;
            }
            return new Window(current.generation(), current.startedAt(), current.failures() + 1,
                    Math.max(0, current.inFlight() - 1));
        });
        cleanupExpired(now);
    }

    public void recordSuccess(Attempt attempt) {
        windows.remove(attempt.key);
    }

    public int trackedKeys() {
        return windows.size();
    }

    private void cleanupExpired(Instant now) {
        Instant last = lastCleanup.get();
        if (Duration.between(last, now).compareTo(WINDOW) < 0 || !lastCleanup.compareAndSet(last, now)) {
            return;
        }
        purgeExpired(now);
    }

    private void reclaimExpired(Instant now) {
        Instant last = lastReclaim.get();
        if (Duration.between(last, now).compareTo(RECLAIM_INTERVAL) < 0
                || !lastReclaim.compareAndSet(last, now)) {
            return;
        }
        purgeExpired(now);
    }

    private void purgeExpired(Instant now) {
        windows.forEach((key, window) -> {
            if (!now.isBefore(window.startedAt().plus(WINDOW))) {
                windows.remove(key, window);
            }
        });
    }

    private static long retryAfterSeconds(Instant now, Instant windowStart) {
        long millis = Math.max(0, Duration.between(now, windowStart.plus(WINDOW)).toMillis());
        return Math.max(1, Math.min(WINDOW.toSeconds(), (millis + 999) / 1000));
    }

    public static final class Attempt {

        private final String key;
        private final long generation;

        private Attempt(String key, long generation) {
            this.key = key;
            this.generation = generation;
        }
    }

    private record Window(long generation, Instant startedAt, int failures, int inFlight) {

        int usedSlots() {
            return failures + inFlight;
        }
    }
}
