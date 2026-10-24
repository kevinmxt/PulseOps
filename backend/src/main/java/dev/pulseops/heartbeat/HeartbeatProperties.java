package dev.pulseops.heartbeat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("pulseops.heartbeat")
public record HeartbeatProperties(
        @DefaultValue("false") boolean requireHttps,
        @DefaultValue("5s") Duration tickInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("true") boolean schedulingEnabled) {

    static final Duration MIN_TICK_INTERVAL = Duration.ofMillis(100);
    static final int MAX_BATCH_SIZE = 500;

    public HeartbeatProperties {
        if (tickInterval == null || tickInterval.compareTo(MIN_TICK_INTERVAL) < 0) {
            throw new IllegalArgumentException("pulseops.heartbeat.tick-interval must be at least 100ms");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "pulseops.heartbeat.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
    }
}
