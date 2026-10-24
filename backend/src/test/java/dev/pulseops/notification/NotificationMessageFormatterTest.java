package dev.pulseops.notification;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.CheckFailureType;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMessageFormatterTest {

    private static final Instant STARTED_AT = Instant.parse("2026-10-21T08:00:00Z");
    private final NotificationMessageFormatter formatter = new NotificationMessageFormatter();

    @Test
    void formatsOpenedMessageWithMonitorFailureAndTime() {
        IncidentEntity incident = incident("UNEXPECTED_STATUS", "连续 3 次探测失败，最新失败：非预期状态码");
        MonitorEntity monitor = monitor("checkout api");

        String message = formatter.format(NotificationEventType.INCIDENT_OPENED, incident, monitor);

        assertThat(message)
                .contains("故障告警")
                .contains("checkout api")
                .contains("2026-10-21 08:00:00 UTC")
                .contains("非预期状态码")
                .contains("连续 3 次探测失败");
    }

    @Test
    void formatsResolvedMessageWithRecoveryTime() {
        IncidentEntity incident = incident("CONNECTION_FAILED", "连续 3 次探测失败");
        incident.resolve(STARTED_AT.plusSeconds(120));

        String message = formatter.format(NotificationEventType.INCIDENT_RESOLVED, incident, monitor("api"));

        assertThat(message)
                .contains("故障恢复")
                .contains("恢复时间：2026-10-21 08:02:00 UTC");
    }

    @Test
    void truncatesMessagesToTelegramLimit() {
        IncidentEntity incident = incident("CONNECTION_FAILED", "x".repeat(5000));

        String message = formatter.format(NotificationEventType.INCIDENT_OPENED, incident, monitor("api"));

        assertThat(message).hasSize(4096);
    }

    private static IncidentEntity incident(String failureType, String summary) {
        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(1L);
        incident.setStartedAt(STARTED_AT);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setFailureType(CheckFailureType.valueOf(failureType));
        incident.setSummary(summary);
        return incident;
    }

    private static MonitorEntity monitor(String name) {
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName(name);
        monitor.setCurrentStatus(MonitorStatus.UP);
        return monitor;
    }
}
