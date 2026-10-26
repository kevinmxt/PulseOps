package dev.pulseops.cleanup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("pulseops.cleanup")
public record CleanupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("true") boolean schedulingEnabled,
        @DefaultValue("1h") Duration tickInterval,
        @DefaultValue("30") int checkResultRetentionDays,
        @DefaultValue("90") int resolvedIncidentRetentionDays,
        @DefaultValue("1000") int batchSize,
        @DefaultValue("50") int maxBatchesPerTick) {

    static final Duration MIN_TICK_INTERVAL = Duration.ofMinutes(1);
    static final Duration MAX_TICK_INTERVAL = Duration.ofHours(24);
    static final int MIN_RETENTION_DAYS = 1;
    static final int MAX_RETENTION_DAYS = 3650;
    static final int MIN_BATCH_SIZE = 1;
    static final int MAX_BATCH_SIZE = 10000;
    static final int MIN_MAX_BATCHES_PER_TICK = 1;
    static final int MAX_MAX_BATCHES_PER_TICK = 1000;

    public CleanupProperties {
        if (tickInterval == null) {
            tickInterval = Duration.ofHours(1);
        }
        if (tickInterval.compareTo(MIN_TICK_INTERVAL) < 0
                || tickInterval.compareTo(MAX_TICK_INTERVAL) > 0) {
            throw new IllegalStateException(
                    "pulseops.cleanup.tick-interval must be between 1m and 24h");
        }
        requireRange("check-result-retention-days", checkResultRetentionDays,
                MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
        requireRange("resolved-incident-retention-days", resolvedIncidentRetentionDays,
                MIN_RETENTION_DAYS, MAX_RETENTION_DAYS);
        requireRange("batch-size", batchSize, MIN_BATCH_SIZE, MAX_BATCH_SIZE);
        requireRange("max-batches-per-tick", maxBatchesPerTick,
                MIN_MAX_BATCHES_PER_TICK, MAX_MAX_BATCHES_PER_TICK);
    }

    private static void requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalStateException(
                    "pulseops.cleanup." + name + " must be between " + min + " and " + max);
        }
    }
}
