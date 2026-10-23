package dev.pulseops.monitor.api;

import dev.pulseops.monitor.MonitorEntity;

import java.time.Instant;

public record MonitorResponse(
        Long id,
        String name,
        String type,
        String targetUrl,
        String method,
        int intervalSeconds,
        int connectTimeoutMs,
        int responseTimeoutMs,
        int expectedStatusMin,
        int expectedStatusMax,
        boolean enabled,
        int failureThreshold,
        int recoveryThreshold,
        String currentStatus,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        Instant createdAt,
        Instant updatedAt) {

    public static MonitorResponse from(MonitorEntity monitor) {
        return new MonitorResponse(
                monitor.getId(),
                monitor.getName(),
                monitor.getType().name(),
                monitor.getTargetUrl(),
                monitor.getMethod().name(),
                monitor.getIntervalSeconds(),
                monitor.getConnectTimeoutMs(),
                monitor.getResponseTimeoutMs(),
                monitor.getExpectedStatusMin(),
                monitor.getExpectedStatusMax(),
                monitor.isEnabled(),
                monitor.getFailureThreshold(),
                monitor.getRecoveryThreshold(),
                monitor.getCurrentStatus().name(),
                monitor.getLastCheckedAt(),
                monitor.getLastSuccessAt(),
                monitor.getCreatedAt(),
                monitor.getUpdatedAt());
    }
}
