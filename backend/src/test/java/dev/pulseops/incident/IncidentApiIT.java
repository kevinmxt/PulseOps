package dev.pulseops.incident;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.target.TargetPolicy;
import dev.pulseops.notification.NotificationChannel;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import dev.pulseops.notification.NotificationDeliveryStatus;
import dev.pulseops.notification.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static dev.pulseops.support.AuthenticatedApiRequests.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pulseops.probe.scheduling-enabled=false")
@AutoConfigureMockMvc
@Testcontainers
@Import(IncidentApiIT.ApiTestConfiguration.class)
class IncidentApiIT {

    private static final Instant T1 = Instant.parse("2026-10-21T08:00:00Z");
    private static final Instant T2 = Instant.parse("2026-10-21T09:00:00Z");
    private static final Instant T3 = Instant.parse("2026-10-21T10:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MonitorService monitorService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    IncidentRepository incidentRepository;

    @Autowired
    NotificationDeliveryRepository notificationDeliveryRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class ApiTestConfiguration {

        @Bean
        @Primary
        TargetPolicy permissiveTargetPolicy() {
            return target -> {
            };
        }
    }

    @BeforeEach
    void resetState() {
        notificationDeliveryRepository.deleteAll();
        incidentRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void listsIncidentsNewestFirstWithPaginationAndMonitorNames() throws Exception {
        MonitorEntity first = monitor("first");
        MonitorEntity second = monitor("second");
        incident(first.getId(), T1, IncidentStatus.OPEN);
        incident(second.getId(), T3, IncidentStatus.OPEN);
        incident(first.getId(), T2, IncidentStatus.RESOLVED);

        mockMvc.perform(get("/api/v1/incidents").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-21T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].monitorName").value("second"))
                .andExpect(jsonPath("$.items[1].startedAt").value("2026-10-21T09:00:00Z"));
    }

    @Test
    void filtersByStatusAndMonitor() throws Exception {
        MonitorEntity first = monitor("first");
        MonitorEntity second = monitor("second");
        incident(first.getId(), T1, IncidentStatus.OPEN);
        incident(first.getId(), T2, IncidentStatus.RESOLVED);
        incident(second.getId(), T3, IncidentStatus.OPEN);

        mockMvc.perform(get("/api/v1/incidents").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[1].status").value("OPEN"));

        mockMvc.perform(get("/api/v1/incidents").param("monitor_id", first.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].monitorId").value(first.getId()))
                .andExpect(jsonPath("$.items[1].monitorId").value(first.getId()));

        mockMvc.perform(get("/api/v1/incidents")
                        .param("status", "OPEN")
                        .param("monitor_id", first.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].monitorId").value(first.getId()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));
    }

    @Test
    void returnsIncidentDetailWithMonitorName() throws Exception {
        MonitorEntity monitor = monitor("detail");
        IncidentEntity incident = incident(monitor.getId(), T1, IncidentStatus.RESOLVED);

        mockMvc.perform(get("/api/v1/incidents/{id}", incident.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(incident.getId()))
                .andExpect(jsonPath("$.monitorId").value(monitor.getId()))
                .andExpect(jsonPath("$.monitorName").value("detail"))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.failureType").value("CONNECTION_FAILED"))
                .andExpect(jsonPath("$.startedAt").value("2026-10-21T08:00:00Z"))
                .andExpect(jsonPath("$.resolvedAt").value("2026-10-21T08:01:00Z"))
                .andExpect(jsonPath("$.summary").value("连续 3 次探测失败，最新失败：连接失败"));
    }

    @Test
    void returnsDeliveryHistoryInIncidentDetail() throws Exception {
        MonitorEntity monitor = monitor("deliveries");
        IncidentEntity incident = incident(monitor.getId(), T1, IncidentStatus.RESOLVED);
        delivery(incident.getId(), NotificationEventType.INCIDENT_OPENED, T1, T1);
        delivery(incident.getId(), NotificationEventType.INCIDENT_RESOLVED, T2, T2);

        mockMvc.perform(get("/api/v1/incidents/{id}", incident.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(2))
                .andExpect(jsonPath("$.deliveries[0].eventType").value("INCIDENT_RESOLVED"))
                .andExpect(jsonPath("$.deliveries[0].channel").value("TELEGRAM"))
                .andExpect(jsonPath("$.deliveries[0].status").value("SENT"))
                .andExpect(jsonPath("$.deliveries[0].attemptCount").value(1))
                .andExpect(jsonPath("$.deliveries[0].sentAt").value("2026-10-21T09:00:00Z"))
                .andExpect(jsonPath("$.deliveries[1].eventType").value("INCIDENT_OPENED"));
    }

    @Test
    void returnsRetryErrorSummaryForPendingDelivery() throws Exception {
        MonitorEntity monitor = monitor("retry");
        IncidentEntity incident = incident(monitor.getId(), T1, IncidentStatus.OPEN);
        pendingDelivery(incident.getId(), NotificationEventType.INCIDENT_OPENED, T2);

        mockMvc.perform(get("/api/v1/incidents/{id}", incident.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()").value(1))
                .andExpect(jsonPath("$.deliveries[0].status").value("PENDING"))
                .andExpect(jsonPath("$.deliveries[0].attemptCount").value(1))
                .andExpect(jsonPath("$.deliveries[0].lastErrorSummary")
                        .value("HTTP 500 from Telegram"));
    }

    @Test
    void returnsNotFoundForUnknownIncident() throws Exception {
        mockMvc.perform(get("/api/v1/incidents/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INCIDENT_NOT_FOUND"));
    }

    @Test
    void rejectsInvalidStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("status", "broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void rejectsPageSizeAboveTheLimit() throws Exception {
        mockMvc.perform(get("/api/v1/incidents").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private MonitorEntity monitor(String name) {
        return monitorService.create(new MonitorConfigInput(
                name,
                "https://example.com/health",
                "GET",
                60,
                3000,
                10000,
                200,
                299,
                true,
                null,
                null)).monitor();
    }

    private NotificationDeliveryEntity delivery(Long incidentId, NotificationEventType eventType,
                                                Instant attemptedAt, Instant sentAt) {
        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
        delivery.setIncidentId(incidentId);
        delivery.setChannel(NotificationChannel.TELEGRAM);
        delivery.setEventType(eventType);
        delivery.setStatus(NotificationDeliveryStatus.SENT);
        delivery.setAttemptCount(1);
        delivery.setNextAttemptAt(attemptedAt);
        delivery.setLastAttemptAt(attemptedAt);
        delivery.setSentAt(sentAt);
        return notificationDeliveryRepository.save(delivery);
    }

    private NotificationDeliveryEntity pendingDelivery(Long incidentId,
                                                       NotificationEventType eventType,
                                                       Instant attemptedAt) {
        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
        delivery.setIncidentId(incidentId);
        delivery.setChannel(NotificationChannel.TELEGRAM);
        delivery.setEventType(eventType);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setAttemptCount(1);
        delivery.setNextAttemptAt(attemptedAt.plusSeconds(60));
        delivery.setLastAttemptAt(attemptedAt);
        delivery.setLastErrorSummary("HTTP 500 from Telegram");
        return notificationDeliveryRepository.save(delivery);
    }

    private IncidentEntity incident(Long monitorId, Instant startedAt, IncidentStatus status) {
        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(monitorId);
        incident.setStartedAt(startedAt);
        incident.setStatus(status);
        incident.setFailureType(CheckFailureType.CONNECTION_FAILED);
        incident.setSummary("连续 3 次探测失败，最新失败：连接失败");
        if (status == IncidentStatus.RESOLVED) {
            incident.setResolvedAt(startedAt.plusSeconds(60));
        }
        return incidentRepository.save(incident);
    }
}
