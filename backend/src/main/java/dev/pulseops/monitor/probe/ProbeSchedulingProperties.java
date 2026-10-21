package dev.pulseops.monitor.probe;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "pulseops.probe")
public record ProbeSchedulingProperties(
        @DefaultValue("1s") Duration tickInterval,
        @DefaultValue("200") int batchSize,
        @DefaultValue("8") int poolSize,
        @DefaultValue("100") int queueCapacity) {

    static final Duration MIN_TICK_INTERVAL = Duration.ofMillis(100);

    public ProbeSchedulingProperties {
        if (tickInterval == null || tickInterval.compareTo(MIN_TICK_INTERVAL) < 0) {
            throw new IllegalArgumentException("pulseops.probe.tick-interval must be at least 100ms");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("pulseops.probe.batch-size must be at least 1");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("pulseops.probe.pool-size must be at least 1");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("pulseops.probe.queue-capacity must not be negative");
        }
    }
}
