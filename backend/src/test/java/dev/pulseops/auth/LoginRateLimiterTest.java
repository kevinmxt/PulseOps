package dev.pulseops.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginRateLimiterTest {

    private static final Instant START = Instant.parse("2026-10-25T08:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final LoginRateLimiter limiter = new LoginRateLimiter(clock);

    @Test
    void rejectsTheSixthAttemptWithinTheWindow() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            LoginRateLimiter.Attempt reservation = limiter.beginAttempt(key);
            limiter.recordFailure(reservation);
        }

        assertThatThrownBy(() -> limiter.beginAttempt(key))
                .isInstanceOf(LoginRateLimitedException.class)
                .satisfies(ex -> assertThat(((LoginRateLimitedException) ex).getRetryAfterSeconds())
                        .isEqualTo(LoginRateLimiter.WINDOW.toSeconds()));
    }

    @Test
    void countsInFlightAttemptsTowardsTheQuota() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.beginAttempt(key);
        }

        assertThatThrownBy(() -> limiter.beginAttempt(key))
                .isInstanceOf(LoginRateLimitedException.class)
                .satisfies(ex -> assertThat(((LoginRateLimitedException) ex).getRetryAfterSeconds())
                        .isPositive());
    }

    @Test
    void settledFailuresReleaseInFlightSlotsButKeepCountingTowardsTheQuota() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");

        LoginRateLimiter.Attempt first = limiter.beginAttempt(key);
        LoginRateLimiter.Attempt second = limiter.beginAttempt(key);
        limiter.beginAttempt(key);
        limiter.beginAttempt(key);
        limiter.recordFailure(first);
        limiter.recordFailure(second);

        limiter.beginAttempt(key);

        assertThatThrownBy(() -> limiter.beginAttempt(key))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void concurrentAttemptsCannotExceedTheQuota() throws Exception {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        int threads = 24;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        limiter.beginAttempt(key);
                        return true;
                    } catch (LoginRateLimitedException ex) {
                        assertThat(ex.getRetryAfterSeconds()).isPositive();
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(LoginRateLimiter.MAX_ATTEMPTS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void allowsRetriesAfterTheWindowExpires() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        exhaust(key);

        clock.advance(LoginRateLimiter.WINDOW);

        assertThatCode(() -> limiter.beginAttempt(key)).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS - 1; attempt++) {
            limiter.recordFailure(limiter.beginAttempt(key));
        }

        limiter.recordSuccess(limiter.beginAttempt(key));
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.recordFailure(limiter.beginAttempt(key));
        }

        assertThatThrownBy(() -> limiter.beginAttempt(key))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void successfulLoginLeavesOtherKeysUntouched() {
        String exhausted = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        String otherKey = LoginRateLimiter.keyFor("admin", "10.0.0.7");
        exhaust(exhausted);
        limiter.recordFailure(limiter.beginAttempt(otherKey));

        limiter.recordSuccess(limiter.beginAttempt(LoginRateLimiter.keyFor("other", "127.0.0.1")));

        assertThatThrownBy(() -> limiter.beginAttempt(exhausted))
                .isInstanceOf(LoginRateLimitedException.class);
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS - 1; attempt++) {
            limiter.recordFailure(limiter.beginAttempt(otherKey));
        }
        assertThatThrownBy(() -> limiter.beginAttempt(otherKey))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void successDoesNotResurrectStaleFailuresFromTheClearedWindow() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        LoginRateLimiter.Attempt stale = limiter.beginAttempt(key);
        LoginRateLimiter.Attempt successful = limiter.beginAttempt(key);

        limiter.recordSuccess(successful);
        limiter.recordFailure(stale);

        assertThatCode(() -> limiter.beginAttempt(key)).doesNotThrowAnyException();
    }

    @Test
    void failuresFromExpiredWindowsDoNotCountTowardsTheNextWindow() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        LoginRateLimiter.Attempt stale = limiter.beginAttempt(key);

        clock.advance(LoginRateLimiter.WINDOW);
        limiter.recordFailure(stale);

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.recordFailure(limiter.beginAttempt(key));
        }

        assertThatThrownBy(() -> limiter.beginAttempt(key))
                .isInstanceOf(LoginRateLimitedException.class);
    }

    @Test
    void countsUsernamesAndRemoteAddressesIndependently() {
        exhaust(LoginRateLimiter.keyFor("admin", "127.0.0.1"));

        assertThatCode(() -> limiter.beginAttempt(LoginRateLimiter.keyFor("admin", "10.0.0.7")))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.beginAttempt(LoginRateLimiter.keyFor("other", "127.0.0.1")))
                .doesNotThrowAnyException();
    }

    @Test
    void normalizesUsernamesAndBoundsOversizedInput() {
        assertThat(LoginRateLimiter.keyFor("  admin  ", "127.0.0.1"))
                .isEqualTo(LoginRateLimiter.keyFor("admin", "127.0.0.1"));
        assertThat(LoginRateLimiter.keyFor("x".repeat(200), "127.0.0.1"))
                .isEqualTo(LoginRateLimiter.keyFor("x".repeat(64), "127.0.0.1"));
    }

    private void exhaust(String key) {
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            limiter.recordFailure(limiter.beginAttempt(key));
        }
    }

    private static final class MutableClock extends Clock {

        private volatile Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
