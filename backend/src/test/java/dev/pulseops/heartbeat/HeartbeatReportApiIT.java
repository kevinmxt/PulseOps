package dev.pulseops.heartbeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class HeartbeatReportApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

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
    void reportsHeartbeatAndUpdatesMonitorState() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("nightly", 60, 60);
        Instant before = Instant.now().minusSeconds(1);

        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        Instant after = Instant.now().plusSeconds(1);
        MonitorEntity reloaded = monitorRepository.findById(monitor.id()).orElseThrow();
        assertThat(reloaded.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(reloaded.getLastHeartbeatAt()).isBetween(before, after);
        assertThat(reloaded.getLastCheckedAt()).isEqualTo(reloaded.getLastHeartbeatAt());
        assertThat(reloaded.getLastSuccessAt()).isEqualTo(reloaded.getLastHeartbeatAt());
        assertThat(reloaded.getNextCheckAt())
                .isEqualTo(reloaded.getLastHeartbeatAt().plusSeconds(60 + 60));

        List<CheckResultEntity> results = checkResults(monitor.id());
        assertThat(results).hasSize(1);
        CheckResultEntity result = results.getFirst();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailureType()).isNull();
        assertThat(result.getHttpStatusCode()).isNull();
        assertThat(result.getErrorSummary()).isNull();
        assertThat(result.getDurationMs()).isZero();
        assertThat(result.getStartedAt()).isEqualTo(reloaded.getLastHeartbeatAt());
        assertThat(result.getFinishedAt()).isEqualTo(reloaded.getLastHeartbeatAt());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/monitors/{id}", monitor.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("UP"))
                .andExpect(jsonPath("$.lastHeartbeatAt").isNotEmpty());
    }

    @Test
    void rejectsInvalidRevokedDisabledAndUnknownTokensWithSameNotFound() throws Exception {
        String invalid = reportErrorCodeAndMessage("not-a-real-token-" + UUID.randomUUID());
        String unknown = reportErrorCodeAndMessage("A".repeat(43));

        HeartbeatMonitor revoked = createHeartbeat("revoked", null, null);
        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", revoked.id()))
                .andExpect(status().isNoContent());
        String revokedMessage = reportErrorCodeAndMessage(revoked.token());

        HeartbeatMonitor disabled = createHeartbeat("disabled", null, null);
        mockMvc.perform(put("/api/v1/monitors/{id}", disabled.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"disabled","enabled":false}
                                """))
                .andExpect(status().isOk());
        String disabledMessage = reportErrorCodeAndMessage(disabled.token());

        assertThat(invalid).isEqualTo("heartbeat not found");
        assertThat(unknown).isEqualTo(invalid);
        assertThat(revokedMessage).isEqualTo(invalid);
        assertThat(disabledMessage).isEqualTo(invalid);
        assertThat(checkResults(revoked.id())).isEmpty();
        assertThat(checkResults(disabled.id())).isEmpty();
    }

    @Test
    void rotatesTokenAndInvalidatesOldOneImmediately() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("rotate", null, null);

        String newToken = rotate(monitor.id());
        assertThat(newToken).hasSize(43).isNotEqualTo(monitor.token());

        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HEARTBEAT_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/heartbeats/{token}", newToken))
                .andExpect(status().isNoContent());

        assertThat(activeCredentialCount(monitor.id())).isEqualTo(1);
        assertThat(totalCredentialCount(monitor.id())).isEqualTo(2);
        assertThat(revokedCredentialCount(monitor.id(), sha256Hex(monitor.token()))).isEqualTo(1);
    }

    @Test
    void rotatesTokenForDisabledMonitorAndRejectsHttpMonitor() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("disabled-rotate", null, null);
        mockMvc.perform(put("/api/v1/monitors/{id}", monitor.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"disabled-rotate","enabled":false}
                                """))
                .andExpect(status().isOk());

        assertThat(rotate(monitor.id())).hasSize(43);

        long httpId = createHttp("http");
        mockMvc.perform(post("/api/v1/monitors/{id}/heartbeat-token", httpId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_HEARTBEAT_MONITOR"));
        mockMvc.perform(post("/api/v1/monitors/{id}/heartbeat-token", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"));
    }

    @Test
    void revokesTokenIdempotentlyAndRejectsHttpMonitor() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("revoke", null, null);

        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", monitor.id()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", monitor.id()))
                .andExpect(status().isNoContent());
        assertThat(activeCredentialCount(monitor.id())).isZero();

        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HEARTBEAT_NOT_FOUND"));

        long httpId = createHttp("http-revoke");
        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", httpId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NOT_HEARTBEAT_MONITOR"));
        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"));
    }

    @Test
    void revokesTokenForDisabledMonitor() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("disabled-revoke", null, null);
        mockMvc.perform(put("/api/v1/monitors/{id}", monitor.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"disabled-revoke","enabled":false}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/monitors/{id}/heartbeat-token", monitor.id()))
                .andExpect(status().isNoContent());
        assertThat(activeCredentialCount(monitor.id())).isZero();
    }

    @Test
    void limitsReportsPerTokenIncludingInvalidTokens() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("rate-valid", null, null);
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                    .andExpect(status().isNoContent());
        }
        MvcResult limited = mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        assertThat(retryAfterSeconds(limited)).isBetween(1L, 60L);

        String invalid = "B".repeat(43);
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(post("/api/v1/heartbeats/{token}", invalid))
                    .andExpect(status().isNotFound());
        }
        MvcResult invalidLimited = mockMvc.perform(post("/api/v1/heartbeats/{token}", invalid))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        assertThat(retryAfterSeconds(invalidLimited)).isBetween(1L, 60L);
    }

    @Test
    void rejectsRequestBodiesLargerThanOneKilobyte() throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("payload", null, null);

        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("a".repeat(1025)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(monitorRepository.findById(monitor.id()).orElseThrow().getLastHeartbeatAt()).isNull();
        assertThat(checkResults(monitor.id())).isEmpty();

        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("a".repeat(1024)))
                .andExpect(status().isNoContent());
    }

    @Test
    void neverLogsFullTokens(CapturedOutput output) throws Exception {
        HeartbeatMonitor monitor = createHeartbeat("logs", null, null);
        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isNoContent());
        String rotated = rotate(monitor.id());
        mockMvc.perform(post("/api/v1/heartbeats/{token}", rotated))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/heartbeats/{token}", monitor.token()))
                .andExpect(status().isNotFound());

        assertThat(output.getAll())
                .doesNotContain(monitor.token())
                .doesNotContain(rotated);
    }

    private String reportErrorCodeAndMessage(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/heartbeats/{token}", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HEARTBEAT_NOT_FOUND"))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("message").asText();
    }

    private String rotate(long monitorId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/monitors/{id}/heartbeat-token", monitorId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartbeatToken").isString())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("heartbeatToken").asText();
    }

    private HeartbeatMonitor createHeartbeat(String name, Integer interval, Integer grace) throws Exception {
        String body = """
                {"name":"%s","type":"HEARTBEAT"%s%s}
                """.formatted(name,
                interval == null ? "" : ",\"intervalSeconds\":" + interval,
                grace == null ? "" : ",\"heartbeatGraceSeconds\":" + grace);
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.heartbeatToken").isString())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return new HeartbeatMonitor(json.get("id").asLong(), json.get("heartbeatToken").asText());
    }

    private long createHttp(String name) throws Exception {
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","targetUrl":"http://8.8.8.8/health"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private List<CheckResultEntity> checkResults(long monitorId) {
        return checkResultRepository.findByMonitorId(monitorId, PageRequest.of(0, 10)).getContent();
    }

    private long activeCredentialCount(long monitorId) {
        return queryCount("select count(*) from heartbeat_credential where monitor_id = ? and revoked_at is null",
                monitorId);
    }

    private long totalCredentialCount(long monitorId) {
        return queryCount("select count(*) from heartbeat_credential where monitor_id = ?", monitorId);
    }

    private long revokedCredentialCount(long monitorId, String tokenHash) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from heartbeat_credential where monitor_id = ? and token_hash = ? "
                        + "and revoked_at is not null",
                Long.class, monitorId, tokenHash);
        return count == null ? 0 : count;
    }

    private long queryCount(String sql, long monitorId) {
        Long count = jdbcTemplate.queryForObject(sql, Long.class, monitorId);
        return count == null ? 0 : count;
    }

    private static long retryAfterSeconds(MvcResult result) {
        String value = result.getResponse().getHeader("Retry-After");
        return value == null ? -1 : Long.parseLong(value);
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record HeartbeatMonitor(long id, String token) {
    }
}
