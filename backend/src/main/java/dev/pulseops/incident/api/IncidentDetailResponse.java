package dev.pulseops.incident.api;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.notification.api.NotificationDeliveryResponse;

import java.time.Instant;
import java.util.List;

public record IncidentDetailResponse(
        Long id,
        Long monitorId,
        String monitorName,
        Instant startedAt,
        Instant resolvedAt,
        String status,
        String failureType,
        String summary,
        List<NotificationDeliveryResponse> deliveries) {

    public static IncidentDetailResponse from(IncidentEntity incident, String monitorName,
                                              List<NotificationDeliveryResponse> deliveries) {
        return new IncidentDetailResponse(
                incident.getId(),
                incident.getMonitorId(),
                monitorName,
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getStatus().name(),
                incident.getFailureType().name(),
                incident.getSummary(),
                deliveries);
    }
}
