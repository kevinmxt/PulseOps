package dev.pulseops.incident.api;

import dev.pulseops.incident.IncidentEntity;

import java.time.Instant;

public record IncidentResponse(
        Long id,
        Long monitorId,
        String monitorName,
        Instant startedAt,
        Instant resolvedAt,
        String status,
        String failureType,
        String summary) {

    public static IncidentResponse from(IncidentEntity incident, String monitorName) {
        return new IncidentResponse(
                incident.getId(),
                incident.getMonitorId(),
                monitorName,
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getStatus().name(),
                incident.getFailureType().name(),
                incident.getSummary());
    }
}
