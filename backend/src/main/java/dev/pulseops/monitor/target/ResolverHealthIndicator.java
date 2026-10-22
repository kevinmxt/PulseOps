package dev.pulseops.monitor.target;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class ResolverHealthIndicator implements HealthIndicator {

    private final TimeoutHostResolver resolver;
    private final ResolverHealthProperties properties;
    private final Clock clock;

    public ResolverHealthIndicator(TimeoutHostResolver resolver, ResolverHealthProperties properties,
                                   Clock clock) {
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Health health() {
        if (!resolver.isExhausted()) {
            return Health.up().build();
        }
        Instant since = resolver.exhaustedSince();
        if (since == null) {
            return Health.up().build();
        }
        Duration exhaustedFor = Duration.between(since, clock.instant());
        return exhaustedFor.compareTo(properties.downAfter()) >= 0
                ? Health.down().build()
                : Health.up().build();
    }
}
