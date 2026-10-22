package dev.pulseops.monitor.api;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import dev.pulseops.monitor.probe.CheckFailureType;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CheckResultApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void listsCheckResultsNewestFirstWithPagination() throws Exception {
        MonitorEntity monitor = monitorRepository.save(newMonitor("paged"));
        checkResult(monitor.getId(), Instant.parse("2026-10-21T08:00:00Z"), true);
        checkResult(monitor.getId(), Instant.parse("2026-10-21T09:00:00Z"), false);
        checkResult(monitor.getId(), Instant.parse("2026-10-21T10:00:00Z"), true);

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-21T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].success").value(true))
                .andExpect(jsonPath("$.items[0].durationMs").value(1000))
                .andExpect(jsonPath("$.items[1].startedAt").value("2026-10-21T09:00:00Z"))
                .andExpect(jsonPath("$.items[1].success").value(false))
                .andExpect(jsonPath("$.items[1].failureType").value("CONNECTION_FAILED"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-21T08:00:00Z"));
    }

    @Test
    void defaultsToTwentyItemsPerPage() throws Exception {
        MonitorEntity monitor = monitorRepository.save(newMonitor("many"));
        for (int i = 0; i < 21; i++) {
            checkResult(monitor.getId(), Instant.parse("2026-10-21T08:00:00Z").plusSeconds(i * 60L), true);
        }

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.totalElements").value(21));
    }

    @Test
    void returnsEmptyPageWhenMonitorHasNoHistory() throws Exception {
        MonitorEntity monitor = monitorRepository.save(newMonitor("empty"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void returnsNotFoundForMissingMonitor() throws Exception {
        mockMvc.perform(get("/api/v1/monitors/{id}/checks", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("999999")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void doesNotIncludeOtherMonitorsResults() throws Exception {
        MonitorEntity first = monitorRepository.save(newMonitor("first"));
        MonitorEntity second = monitorRepository.save(newMonitor("second"));
        checkResult(first.getId(), Instant.parse("2026-10-21T08:00:00Z"), true);
        checkResult(first.getId(), Instant.parse("2026-10-21T09:00:00Z"), true);
        checkResult(second.getId(), Instant.parse("2026-10-21T10:00:00Z"), true);

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", first.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-21T09:00:00Z"))
                .andExpect(jsonPath("$.items[1].startedAt").value("2026-10-21T08:00:00Z"));
    }

    @Test
    void rejectsInvalidPaginationParameters() throws Exception {
        MonitorEntity monitor = monitorRepository.save(newMonitor("paging"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()).param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", monitor.getId()).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    private static MonitorEntity newMonitor(String name) {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName(name);
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

    private void checkResult(Long monitorId, Instant startedAt, boolean success) {
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(startedAt);
        result.setFinishedAt(startedAt.plusSeconds(1));
        result.setDurationMs(1000);
        result.setSuccess(success);
        result.setFailureType(success ? null : CheckFailureType.CONNECTION_FAILED);
        checkResultRepository.save(result);
    }
}
