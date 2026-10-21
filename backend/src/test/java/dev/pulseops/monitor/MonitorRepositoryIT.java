package dev.pulseops.monitor;

import dev.pulseops.monitor.config.HttpMethod;
import org.flywaydb.core.Flyway;
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
class MonitorRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    Flyway flyway;

    @Autowired
    MonitorRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void appliesV2MigrationAndPersistsMonitor() {
        assertThat(flyway.info().applied())
                .anySatisfy(info -> assertThat(info.getVersion().getVersion()).isEqualTo("2"));

        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("API");
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

        MonitorEntity saved = repository.save(monitor);

        assertThat(saved.getId()).isNotNull();
        MonitorEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("API");
        assertThat(reloaded.getTargetUrl()).isEqualTo("http://8.8.8.8/health");
        assertThat(reloaded.getMethod()).isEqualTo(HttpMethod.GET);
        assertThat(reloaded.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(reloaded.getNextCheckAt()).isEqualTo(now);
        assertThat(reloaded.getCreatedAt()).isEqualTo(now);
    }

    @Test
    void createsPartialIndexForDueMonitors() {
        String definition = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_monitor_next_check_at'",
                String.class);

        assertThat(definition)
                .contains("next_check_at")
                .contains("WHERE")
                .contains("enabled");
    }

    @Test
    void enforcesConfigurationRangesInDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO monitor (
                    name, type, target_url, method, interval_seconds,
                    connect_timeout_ms, response_timeout_ms,
                    expected_status_min, expected_status_max,
                    enabled, current_status, next_check_at, created_at, updated_at
                ) VALUES (
                    'invalid', 'HTTP', 'http://8.8.8.8', 'GET', 9,
                    3000, 10000, 200, 299,
                    TRUE, 'UNKNOWN', NOW(), NOW(), NOW()
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
