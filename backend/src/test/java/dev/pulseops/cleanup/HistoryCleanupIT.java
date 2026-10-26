package dev.pulseops.cleanup;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.notification.NotificationChannel;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import dev.pulseops.notification.NotificationDeliveryStatus;
import dev.pulseops.notification.NotificationEventType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.notification.scheduling-enabled=false",
        "pulseops.heartbeat.scheduling-enabled=false",
        "pulseops.cleanup.scheduling-enabled=false",
        "pulseops.cleanup.batch-size=2",
        "pulseops.cleanup.max-batches-per-tick=3"
})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(HistoryCleanupIT.CleanupTestConfiguration.class)
class HistoryCleanupIT {

    private static final Instant NOW = Instant.parse("2026-10-26T08:00:00Z");
    private static final Instant CHECK_RESULT_CUTOFF = NOW.minus(Duration.ofDays(30));
    private static final Instant INCIDENT_CUTOFF = NOW.minus(Duration.ofDays(90));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    CleanupService cleanupService;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    IncidentRepository incidentRepository;

    @Autowired
    NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class CleanupTestConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        notificationDeliveryRepository.deleteAll();
        incidentRepository.deleteAll();
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void addsRetentionIndexes() {
        String checkResultIndex = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_check_result_started_at'",
                String.class);
        assertThat(checkResultIndex)
                .contains("(started_at)")
                .doesNotContain("monitor_id");

        String incidentIndex = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_incident_resolved_at'",
                String.class);
        assertThat(incidentIndex)
                .contains("(resolved_at)")
                .contains("'RESOLVED'");
    }

    @Test
    void deletesOnlyResultsStrictlyBeforeTheRetentionCutoff() {
        MonitorEntity monitor = createMonitor("results-boundary");
        saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.minusMillis(1));
        CheckResultEntity boundary = saveResult(monitor.getId(), CHECK_RESULT_CUTOFF);
        CheckResultEntity fresh = saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.plusMillis(1));

        CleanupService.CleanupTickResult result = cleanupService.tick();

        assertThat(result.checkResultsDeleted()).isEqualTo(1);
        assertThat(checkResultRepository.findAll())
                .extracting(CheckResultEntity::getId)
                .containsExactlyInAnyOrder(boundary.getId(), fresh.getId());
    }

    @Test
    void stopsAtMaxBatchesPerTick() {
        MonitorEntity monitor = createMonitor("batch-limit");
        for (int i = 0; i < 10; i++) {
            saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.minusSeconds(60L + i));
        }

        assertThat(cleanupService.tick().checkResultsDeleted()).isEqualTo(6);
        assertThat(checkResultRepository.count()).isEqualTo(4);

        assertThat(cleanupService.tick().checkResultsDeleted()).isEqualTo(4);
        assertThat(checkResultRepository.count()).isZero();
    }

    @Test
    void sharesBatchBudgetAcrossTablesWithinOneTick() {
        MonitorEntity monitor = createMonitor("shared-budget");
        for (int i = 0; i < 10; i++) {
            saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.minusSeconds(120L + i));
        }
        for (int i = 0; i < 10; i++) {
            saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                    INCIDENT_CUTOFF.minusSeconds(240L + i),
                    INCIDENT_CUTOFF.minusSeconds(120L + i));
        }

        CleanupService.CleanupTickResult first = cleanupService.tick();
        assertThat(first.checkResultsDeleted()).isEqualTo(6);
        assertThat(first.resolvedIncidentsDeleted()).isZero();

        CleanupService.CleanupTickResult second = cleanupService.tick();
        assertThat(second.checkResultsDeleted()).isEqualTo(4);
        assertThat(second.resolvedIncidentsDeleted()).isEqualTo(2);

        cleanupService.tick();
        cleanupService.tick();
        assertThat(checkResultRepository.count()).isZero();
        assertThat(incidentRepository.count()).isZero();
    }

    @Test
    void deletesResolvedIncidentsAndCascadesDeliveries() {
        MonitorEntity monitor = createMonitor("incident-cascade");
        IncidentEntity expired = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                INCIDENT_CUTOFF.minusSeconds(120), INCIDENT_CUTOFF.minusMillis(1));
        IncidentEntity boundary = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                INCIDENT_CUTOFF.minusSeconds(120), INCIDENT_CUTOFF);
        IncidentEntity recent = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                NOW.minusSeconds(120), NOW.minusSeconds(60));
        IncidentEntity longRunning = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                INCIDENT_CUTOFF.minusSeconds(3650L * 24 * 60 * 60), NOW.minusSeconds(30));
        saveDelivery(expired.getId(), NotificationDeliveryStatus.SENT);
        saveDelivery(boundary.getId(), NotificationDeliveryStatus.SENT);

        CleanupService.CleanupTickResult result = cleanupService.tick();

        assertThat(result.resolvedIncidentsDeleted()).isEqualTo(1);
        assertThat(incidentRepository.findAll())
                .extracting(IncidentEntity::getId)
                .containsExactlyInAnyOrder(boundary.getId(), recent.getId(), longRunning.getId());
        assertThat(notificationDeliveryRepository.findAll())
                .extracting(NotificationDeliveryEntity::getIncidentId)
                .containsExactly(boundary.getId());
    }

    @Test
    void cleansAllDataTypesInOneTick() {
        MonitorEntity monitor = createMonitor("multi-table");
        saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.minusSeconds(60));
        IncidentEntity expired = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                INCIDENT_CUTOFF.minusSeconds(120), INCIDENT_CUTOFF.minusSeconds(60));
        saveDelivery(expired.getId(), NotificationDeliveryStatus.PENDING);

        CleanupService.CleanupTickResult result = cleanupService.tick();

        assertThat(result.checkResultsDeleted()).isEqualTo(1);
        assertThat(result.resolvedIncidentsDeleted()).isEqualTo(1);
        assertThat(result.pendingDeliveriesDeleted()).isEqualTo(1);
        assertThat(checkResultRepository.count()).isZero();
        assertThat(incidentRepository.count()).isZero();
        assertThat(notificationDeliveryRepository.count()).isZero();
    }

    @Test
    void neverDeletesOpenIncidentsByRetention() {
        MonitorEntity monitor = createMonitor("open-protection");
        IncidentEntity open = saveIncident(monitor.getId(), IncidentStatus.OPEN,
                INCIDENT_CUTOFF.minusSeconds(3650L * 24 * 60 * 60), null);
        saveDelivery(open.getId(), NotificationDeliveryStatus.PENDING);

        CleanupService.CleanupTickResult result = cleanupService.tick();

        assertThat(result.resolvedIncidentsDeleted()).isZero();
        assertThat(incidentRepository.findById(open.getId())).isPresent();
        assertThat(notificationDeliveryRepository.count()).isEqualTo(1);
    }

    @Test
    void deletesAndCountsPendingDeliveriesOfExpiredIncidents(CapturedOutput output) {
        MonitorEntity monitor = createMonitor("pending-deliveries");
        IncidentEntity expired = saveIncident(monitor.getId(), IncidentStatus.RESOLVED,
                INCIDENT_CUTOFF.minusSeconds(120), INCIDENT_CUTOFF.minusSeconds(60));
        saveDelivery(expired.getId(), NotificationDeliveryStatus.PENDING);
        saveDelivery(expired.getId(), NotificationDeliveryStatus.SENT,
                NotificationEventType.INCIDENT_RESOLVED);
        double pendingBefore = removedCounter("notification_delivery");
        double incidentsBefore = removedCounter("resolved_incident");

        CleanupService.CleanupTickResult result = cleanupService.tick();

        assertThat(result.resolvedIncidentsDeleted()).isEqualTo(1);
        assertThat(result.pendingDeliveriesDeleted()).isEqualTo(1);
        assertThat(notificationDeliveryRepository.count()).isZero();
        assertThat(removedCounter("resolved_incident")).isEqualTo(incidentsBefore + 1);
        assertThat(removedCounter("notification_delivery")).isEqualTo(pendingBefore + 1);
        assertThat(output.getOut())
                .contains("Retention cleanup deleted data")
                .contains("pendingDeliveries=1");
    }

    @Test
    void doesNotBlockNewResultsWhileCleanupRuns() throws Exception {
        MonitorEntity monitor = createMonitor("concurrent-writes");
        for (int i = 0; i < 10; i++) {
            saveResult(monitor.getId(), CHECK_RESULT_CUTOFF.minusSeconds(120L + i));
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<CleanupService.CleanupTickResult> tick =
                    executor.submit(() -> cleanupService.tick());

            List<Long> freshIds = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                freshIds.add(saveResult(monitor.getId(), NOW).getId());
            }

            CleanupService.CleanupTickResult result = tick.get(15, TimeUnit.SECONDS);
            assertThat(result.checkResultsDeleted()).isEqualTo(6);
            assertThat(checkResultRepository.findAllById(freshIds)).hasSize(5);
            assertThat(checkResultRepository.count()).isEqualTo(9);
        } finally {
            executor.shutdownNow();
        }
    }

    private MonitorEntity createMonitor(String name) {
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName(name);
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl("http://127.0.0.1:9/health");
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(3000);
        monitor.setResponseTimeoutMs(10000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(NOW);
        monitor.setCreatedAt(NOW);
        monitor.setUpdatedAt(NOW);
        return monitorRepository.save(monitor);
    }

    private CheckResultEntity saveResult(Long monitorId, Instant startedAt) {
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(startedAt);
        result.setFinishedAt(startedAt.plusSeconds(1));
        result.setDurationMs(1000);
        result.setSuccess(true);
        return checkResultRepository.save(result);
    }

    private IncidentEntity saveIncident(Long monitorId, IncidentStatus status,
                                        Instant startedAt, Instant resolvedAt) {
        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(monitorId);
        incident.setStartedAt(startedAt);
        incident.setResolvedAt(resolvedAt);
        incident.setStatus(status);
        incident.setFailureType(CheckFailureType.CONNECTION_FAILED);
        incident.setSummary("连续失败达到阈值");
        return incidentRepository.save(incident);
    }

    private void saveDelivery(Long incidentId, NotificationDeliveryStatus status) {
        saveDelivery(incidentId, status, NotificationEventType.INCIDENT_OPENED);
    }

    private void saveDelivery(Long incidentId, NotificationDeliveryStatus status,
                              NotificationEventType eventType) {
        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
        delivery.setIncidentId(incidentId);
        delivery.setChannel(NotificationChannel.TELEGRAM);
        delivery.setEventType(eventType);
        delivery.setStatus(status);
        delivery.setNextAttemptAt(NOW.minusSeconds(60));
        if (status == NotificationDeliveryStatus.SENT) {
            delivery.setSentAt(NOW.minusSeconds(30));
        }
        notificationDeliveryRepository.save(delivery);
    }

    private double removedCounter(String type) {
        Counter counter = meterRegistry.find("cleanup.removed").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }
}
