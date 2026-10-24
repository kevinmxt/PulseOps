package dev.pulseops.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatRateLimiterTest {

    private static final Instant START = Instant.parse("2026-10-24T00:00:00Z");

    private final MutableClock clock = new MutableClock(START);
    private final HeartbeatRateLimiter limiter = new HeartbeatRateLimiter(clock);

    @Test
    void allowsRequestsUpToTheWindowLimit() {
        for (int i = 0; i < 120; i++) {
            assertThatCode(() -> limiter.acquire("key")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.acquire("key"))
                .isInstanceOfSatisfying(HeartbeatRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isBetween(1L, 60L));
    }

    @Test
    void resetsAfterTheWindow() {
        for (int i = 0; i < 120; i++) {
            limiter.acquire("key");
        }
        assertThatThrownBy(() -> limiter.acquire("key"))
                .isInstanceOf(HeartbeatRateLimitedException.class);

        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.acquire("key")).doesNotThrowAnyException();
    }

    @Test
    void isolatesKeys() {
        for (int i = 0; i < 120; i++) {
            limiter.acquire("first");
        }
        assertThatThrownBy(() -> limiter.acquire("first"))
                .isInstanceOf(HeartbeatRateLimitedException.class);

        assertThatCode(() -> limiter.acquire("second")).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
