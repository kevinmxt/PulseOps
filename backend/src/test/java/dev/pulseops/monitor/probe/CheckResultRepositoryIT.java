package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class CheckResultRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    Flyway flyway;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void appliesV3Migration() {
        assertThat(flyway.info().applied())
                .anySatisfy(info -> assertThat(info.getVersion().getVersion()).isEqualTo("3"));
    }

    @Test
    void createsDescendingMonitorStartedAtIndex() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_check_result_monitor_started_at'",
                String.class);

        assertThat(definition)
                .contains("monitor_id")
                .contains("started_at DESC");
    }

    @Test
    void cascadesDeletionFromMonitor() {
        MonitorEntity monitor = monitorRepository.save(newMonitor());
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitor.getId());
        result.setStartedAt(Instant.parse("2026-10-21T08:00:00Z"));
        result.setFinishedAt(Instant.parse("2026-10-21T08:00:01Z"));
        result.setDurationMs(1000);
        result.setSuccess(true);
        checkResultRepository.save(result);

        monitorRepository.delete(monitor);

        assertThat(checkResultRepository.count()).isZero();
    }

    @Test
    void rejectsInconsistentOutcome() {
        MonitorEntity monitor = monitorRepository.save(newMonitor());

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO check_result (monitor_id, started_at, finished_at, duration_ms, success, failure_type)
                VALUES (?, NOW(), NOW(), 10, TRUE, 'UNEXPECTED_STATUS')
                """, monitor.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static MonitorEntity newMonitor() {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("probe");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl("http://8.8.8.8/health");
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(3000);
        monitor.setResponseTimeoutMs(10000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitor;
    }
}
