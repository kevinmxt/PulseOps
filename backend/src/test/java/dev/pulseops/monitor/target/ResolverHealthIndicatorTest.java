package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResolverHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private final TimeoutHostResolver resolver = mock(TimeoutHostResolver.class);
    private final ResolverHealthProperties properties = new ResolverHealthProperties(Duration.ofSeconds(60));

    @Test
    void reportsUpWhenResolverIsNotExhausted() {
        when(resolver.isExhausted()).thenReturn(false);

        assertThat(indicatorAt(NOW).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsUpBeforeExhaustionReachesThreshold() {
        when(resolver.isExhausted()).thenReturn(true);
        when(resolver.exhaustedSince()).thenReturn(NOW.minusSeconds(59));

        assertThat(indicatorAt(NOW).health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsDownAtThreshold() {
        when(resolver.isExhausted()).thenReturn(true);
        when(resolver.exhaustedSince()).thenReturn(NOW.minusSeconds(60));

        assertThat(indicatorAt(NOW).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownWhenExhaustionExceedsThreshold() {
        when(resolver.isExhausted()).thenReturn(true);
        when(resolver.exhaustedSince()).thenReturn(NOW.minusSeconds(600));

        assertThat(indicatorAt(NOW).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsUpAfterRecovery() {
        when(resolver.isExhausted()).thenReturn(false);

        assertThat(indicatorAt(NOW.plusSeconds(600)).health().getStatus()).isEqualTo(Status.UP);
    }

    private ResolverHealthIndicator indicatorAt(Instant instant) {
        return new ResolverHealthIndicator(resolver, properties, Clock.fixed(instant, ZoneOffset.UTC));
    }
}
