package dev.pulseops.monitor.api;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorType;

import java.time.Instant;

public record MonitorResponse(
        Long id,
        String name,
        String type,
        String targetUrl,
        String method,
        int intervalSeconds,
        Integer connectTimeoutMs,
        Integer responseTimeoutMs,
        Integer expectedStatusMin,
        Integer expectedStatusMax,
        boolean enabled,
        Integer failureThreshold,
        Integer recoveryThreshold,
        Integer heartbeatGraceSeconds,
        String currentStatus,
        Instant lastCheckedAt,
        Instant lastSuccessAt,
        Instant lastHeartbeatAt,
        Instant createdAt,
        Instant updatedAt,
        String heartbeatToken) {

    public static MonitorResponse from(MonitorEntity monitor) {
        return from(monitor, null);
    }

    public static MonitorResponse from(MonitorEntity monitor, String heartbeatToken) {
        boolean http = monitor.getType() == MonitorType.HTTP;
        return new MonitorResponse(
                monitor.getId(),
                monitor.getName(),
                monitor.getType().name(),
                http ? monitor.getTargetUrl() : null,
                http && monitor.getMethod() != null ? monitor.getMethod().name() : null,
                monitor.getIntervalSeconds(),
                http ? monitor.getConnectTimeoutMs() : null,
                http ? monitor.getResponseTimeoutMs() : null,
                http ? monitor.getExpectedStatusMin() : null,
                http ? monitor.getExpectedStatusMax() : null,
                monitor.isEnabled(),
                http ? monitor.getFailureThreshold() : null,
                http ? monitor.getRecoveryThreshold() : null,
                monitor.getHeartbeatGraceSeconds(),
                monitor.getCurrentStatus().name(),
                monitor.getLastCheckedAt(),
                monitor.getLastSuccessAt(),
                monitor.getLastHeartbeatAt(),
                monitor.getCreatedAt(),
                monitor.getUpdatedAt(),
                heartbeatToken);
    }
}
