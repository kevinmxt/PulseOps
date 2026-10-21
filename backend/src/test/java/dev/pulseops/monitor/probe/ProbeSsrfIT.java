package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProbeSsrfIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/health",
            "http://10.0.0.1/health",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/health"
    })
    void blocksRestrictedTargetAtExecutionTime(String targetUrl) {
        MonitorEntity monitor = saveMonitor(targetUrl);

        probeService.probe(monitor.getId());

        CheckResultEntity result = checkResultRepository.findAll().getFirst();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.CONNECTION_FAILED);
        assertThat(result.getHttpStatusCode()).isNull();
        assertThat(result.getErrorSummary()).isNotBlank();

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    private MonitorEntity saveMonitor(String targetUrl) {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("ssrf probe test");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl(targetUrl);
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(1000);
        monitor.setResponseTimeoutMs(2000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitorRepository.save(monitor);
    }
}
