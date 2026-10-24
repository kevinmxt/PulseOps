package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.CheckFailureType;

import java.time.Instant;

public record ProbeOutcome(
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        boolean success,
        CheckFailureType failureType,
        Integer httpStatusCode,
        String errorSummary) {
}
