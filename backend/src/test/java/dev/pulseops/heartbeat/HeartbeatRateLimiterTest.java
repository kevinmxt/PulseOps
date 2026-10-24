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
            assertThatCode(() -> limiter.recordRequest("key")).doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.recordRequest("key"))
                .isInstanceOfSatisfying(HeartbeatRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(60));
    }

    @Test
    void retryAfterCountsDownWithTheWindow() {
        for (int i = 0; i < 120; i++) {
            limiter.recordRequest("key");
        }
        clock.advance(Duration.ofSeconds(45));

        assertThatThrownBy(() -> limiter.recordRequest("key"))
                .isInstanceOfSatisfying(HeartbeatRateLimitedException.class,
                        e -> assertThat(e.getRetryAfterSeconds()).isEqualTo(15));
    }

    @Test
    void resetsAfterTheWindow() {
        for (int i = 0; i < 120; i++) {
            limiter.recordRequest("key");
        }
        assertThatThrownBy(() -> limiter.recordRequest("key"))
                .isInstanceOf(HeartbeatRateLimitedException.class);

        clock.advance(Duration.ofSeconds(61));

        assertThatCode(() -> limiter.recordRequest("key")).doesNotThrowAnyException();
    }

    @Test
    void isolatesKeys() {
        for (int i = 0; i < 120; i++) {
            limiter.recordRequest("first");
        }
        assertThatThrownBy(() -> limiter.recordRequest("first"))
                .isInstanceOf(HeartbeatRateLimitedException.class);

        assertThatCode(() -> limiter.recordRequest("second")).doesNotThrowAnyException();
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
