package dev.pulseops.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

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

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_FAILURES; attempt++) {
            limiter.checkAllowed(key);
            limiter.recordFailure(key);
        }

        assertThatThrownBy(() -> limiter.checkAllowed(key))
                .isInstanceOf(LoginRateLimitedException.class)
                .satisfies(ex -> assertThat(((LoginRateLimitedException) ex).getRetryAfterSeconds())
                        .isEqualTo(LoginRateLimiter.WINDOW.toSeconds()));
    }

    @Test
    void allowsRetriesAfterTheWindowExpires() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        exhaust(key);

        clock.advance(LoginRateLimiter.WINDOW);

        assertThatCode(() -> limiter.checkAllowed(key)).doesNotThrowAnyException();
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        String key = LoginRateLimiter.keyFor("admin", "127.0.0.1");
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_FAILURES - 1; attempt++) {
            limiter.recordFailure(key);
        }

        limiter.clear(key);
        limiter.checkAllowed(key);
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_FAILURES; attempt++) {
            limiter.checkAllowed(key);
            limiter.recordFailure(key);
        }
    }

    @Test
    void countsUsernamesAndRemoteAddressesIndependently() {
        exhaust(LoginRateLimiter.keyFor("admin", "127.0.0.1"));

        assertThatCode(() -> limiter.checkAllowed(LoginRateLimiter.keyFor("admin", "10.0.0.7")))
                .doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed(LoginRateLimiter.keyFor("other", "127.0.0.1")))
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
        for (int attempt = 0; attempt < LoginRateLimiter.MAX_FAILURES; attempt++) {
            limiter.recordFailure(key);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;

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
