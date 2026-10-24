package dev.pulseops.heartbeat;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorCreationResult;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.UnsupportedMonitorTypeException;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.probe.ProbeService;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import dev.pulseops.notification.NotificationDeliveryStatus;
import dev.pulseops.notification.NotificationEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "pulseops.heartbeat.scheduling-enabled=false",
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.notification.scheduling-enabled=false",
        "pulseops.telegram.bot-token=test-token",
        "pulseops.telegram.chat-id=test-chat"
})
@Testcontainers
@Import(HeartbeatTimeoutIT.TimeConfiguration.class)
class HeartbeatTimeoutIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-24T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MonitorService monitorService;

    @Autowired
    HeartbeatService heartbeatService;

    @Autowired
    HeartbeatScheduler heartbeatScheduler;

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    IncidentRepository incidentRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    NotificationDeliveryRepository deliveryRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeEach
    void cleanDatabase() {
        deliveryRepository.deleteAll();
        incidentRepository.deleteAll();
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        CLOCK.reset();
    }

    @Test
    void timeoutCreatesDownIncidentCheckResultAndNotification() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        Instant deadline = BASE_TIME.plusSeconds(60 + 60);
        CLOCK.advance(Duration.ofSeconds(121));

        heartbeatScheduler.tick();

        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(monitor.getLastCheckedAt()).isEqualTo(CLOCK.instant());
        assertThat(monitor.getLastSuccessAt()).isNull();
        assertThat(monitor.getNextCheckAt()).isEqualTo(CLOCK.instant().plusSeconds(60 + 60));

        List<IncidentEntity> incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        IncidentEntity incident = incidents.getFirst();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getFailureType()).isEqualTo(CheckFailureType.HEARTBEAT_TIMEOUT);
        assertThat(incident.getStartedAt()).isEqualTo(deadline);
        assertThat(incident.getSummary())
                .contains("心跳超时", "从未", "预期间隔 60 秒", "宽限 60 秒")
                .hasSizeLessThanOrEqualTo(200);

        List<CheckResultEntity> results = checkResults(heartbeat.id());
        assertThat(results).hasSize(1);
        CheckResultEntity result = results.getFirst();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.HEARTBEAT_TIMEOUT);
        assertThat(result.getDurationMs()).isZero();
        assertThat(result.getStartedAt()).isEqualTo(CLOCK.instant());
        assertThat(result.getFinishedAt()).isEqualTo(CLOCK.instant());

        List<NotificationDeliveryEntity> deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        NotificationDeliveryEntity delivery = deliveries.getFirst();
        assertThat(delivery.getIncidentId()).isEqualTo(incident.getId());
        assertThat(delivery.getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @Test
    void repeatedScansDoNotDuplicateIncidentOrCheckResult() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        CLOCK.advance(Duration.ofSeconds(121));
        heartbeatScheduler.tick();

        CLOCK.advance(Duration.ofSeconds(120));
        heartbeatScheduler.tick();

        assertThat(incidentRepository.count()).isEqualTo(1);
        assertThat(timeoutResults(heartbeat.id())).hasSize(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getLastCheckedAt()).isEqualTo(CLOCK.instant());
        assertThat(monitor.getNextCheckAt()).isEqualTo(CLOCK.instant().plusSeconds(120));
    }

    @Test
    void validReportResolvesIncidentAndEnqueuesRecovery() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        CLOCK.advance(Duration.ofSeconds(121));
        heartbeatScheduler.tick();
        IncidentEntity incident = incidentRepository.findAll().getFirst();

        CLOCK.advance(Duration.ofSeconds(10));
        Instant reportedAt = CLOCK.instant();
        heartbeatService.report(heartbeat.token());

        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(monitor.getLastHeartbeatAt()).isEqualTo(reportedAt);
        IncidentEntity resolved = incidentRepository.findById(incident.getId()).orElseThrow();
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(reportedAt);

        List<NotificationDeliveryEntity> deliveries = deliveryRepository.findAll();
        assertThat(deliveries).extracting(NotificationDeliveryEntity::getEventType)
                .containsExactlyInAnyOrder(NotificationEventType.INCIDENT_OPENED,
                        NotificationEventType.INCIDENT_RESOLVED);
        assertThat(deliveries).allMatch(delivery -> delivery.getStatus() == NotificationDeliveryStatus.PENDING);

        heartbeatScheduler.tick();
        assertThat(incidentRepository.count()).isEqualTo(1);
        assertThat(timeoutResults(heartbeat.id())).hasSize(1);
    }

    @Test
    void disablingSilentlyClosesIncidentWithoutRecoveryNotification() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        CLOCK.advance(Duration.ofSeconds(121));
        heartbeatScheduler.tick();

        monitorService.update(heartbeat.id(), input("cron", 60, 60, false));

        IncidentEntity incident = incidentRepository.findAll().getFirst();
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        List<NotificationDeliveryEntity> deliveries = deliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
    }

    @Test
    void reenablingResetsToUnknownWithFullWindow() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, false);
        CLOCK.advance(Duration.ofSeconds(1000));
        Instant enabledAt = CLOCK.instant();

        monitorService.update(heartbeat.id(), input("cron", 60, 60, true));

        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(monitor.getNextCheckAt()).isEqualTo(enabledAt.plusSeconds(120));

        heartbeatScheduler.tick();
        assertThat(incidentRepository.count()).isZero();
        assertThat(checkResultRepository.count()).isZero();
    }

    @Test
    void intervalChangeRecomputesDeadlineAndScanUsesIt() {
        Heartbeat heartbeat = createHeartbeat("cron", 600, 60, true);
        CLOCK.advance(Duration.ofSeconds(60));
        heartbeatService.report(heartbeat.token());
        CLOCK.advance(Duration.ofSeconds(40));

        monitorService.update(heartbeat.id(), input("cron", 30, 0, true));
        CLOCK.advance(Duration.ofSeconds(1));
        heartbeatScheduler.tick();

        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        IncidentEntity incident = incidentRepository.findAll().getFirst();
        assertThat(incident.getStartedAt()).isEqualTo(BASE_TIME.plusSeconds(60 + 30));
    }

    @Test
    void overdueChangeWithOpenIncidentOnlyReschedules() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        CLOCK.advance(Duration.ofSeconds(30));
        heartbeatService.report(heartbeat.token());
        CLOCK.advance(Duration.ofSeconds(170));
        heartbeatScheduler.tick();
        assertThat(incidentRepository.count()).isEqualTo(1);
        assertThat(timeoutResults(heartbeat.id())).hasSize(1);

        CLOCK.advance(Duration.ofSeconds(1));
        monitorService.update(heartbeat.id(), input("cron", 30, 0, true));
        heartbeatScheduler.tick();

        assertThat(incidentRepository.count()).isEqualTo(1);
        assertThat(timeoutResults(heartbeat.id())).hasSize(1);
        assertThat(deliveryRepository.count()).isEqualTo(1);
        MonitorEntity monitor = monitorRepository.findById(heartbeat.id()).orElseThrow();
        assertThat(monitor.getLastCheckedAt()).isEqualTo(CLOCK.instant());
        assertThat(monitor.getNextCheckAt()).isEqualTo(CLOCK.instant().plusSeconds(30));
    }

    @Test
    void scanAndReportConcurrentlyNeverDuplicateIncidentOrTimeoutResult() throws Exception {
        Heartbeat heartbeat = createHeartbeat("race", 60, 0, true);
        CLOCK.advance(Duration.ofSeconds(61));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> scan = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                heartbeatScheduler.tick();
                return null;
            });
            Future<?> report = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                heartbeatService.report(heartbeat.token());
                return null;
            });
            scan.get(10, TimeUnit.SECONDS);
            report.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(incidentRepository.findAll())
                .allMatch(incident -> incident.getStatus() == IncidentStatus.RESOLVED);
        assertThat(incidentRepository.count()).isLessThanOrEqualTo(1);
        assertThat(timeoutResults(heartbeat.id())).hasSizeLessThanOrEqualTo(1);
        assertThat(checkResults(heartbeat.id())).hasSizeLessThanOrEqualTo(2);
        assertThat(monitorRepository.findById(heartbeat.id()).orElseThrow().getCurrentStatus())
                .isEqualTo(MonitorStatus.UP);
    }

    @Test
    void heartbeatAndHttpSchedulesDoNotPickEachOther() {
        Heartbeat heartbeat = createHeartbeat("cron", 60, 60, true);
        MonitorCreationResult http = monitorService.create(httpInput());
        CLOCK.advance(Duration.ofSeconds(121));

        List<Long> heartbeatDue = monitorRepository.findDueHeartbeatIds(
                CLOCK.instant(), PageRequest.of(0, 100));

        assertThat(heartbeatDue).contains(heartbeat.id()).doesNotContain(http.monitor().getId());
        assertThatThrownBy(() -> probeService.probe(heartbeat.id()))
                .isInstanceOf(UnsupportedMonitorTypeException.class);
    }

    private Heartbeat createHeartbeat(String name, int interval, int grace, boolean enabled) {
        MonitorCreationResult result = monitorService.create(
                input(name, interval, grace, enabled));
        return new Heartbeat(result.monitor().getId(), result.heartbeatToken());
    }

    private static MonitorConfigInput input(String name, int interval, int grace, boolean enabled) {
        return new MonitorConfigInput(name, null, null, interval, null, null, null, null,
                enabled, null, null, "HEARTBEAT", grace);
    }

    private static MonitorConfigInput httpInput() {
        return new MonitorConfigInput("http", "http://8.8.8.8/health", null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private List<CheckResultEntity> checkResults(long monitorId) {
        return checkResultRepository.findByMonitorId(monitorId, PageRequest.of(0, 20)).getContent();
    }

    private List<CheckResultEntity> timeoutResults(long monitorId) {
        return checkResults(monitorId).stream()
                .filter(result -> result.getFailureType() == CheckFailureType.HEARTBEAT_TIMEOUT)
                .toList();
    }

    private record Heartbeat(long id, String token) {
    }

    private static final class MutableClock extends Clock {

        private Instant now = BASE_TIME;

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        private void reset() {
            now = BASE_TIME;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
