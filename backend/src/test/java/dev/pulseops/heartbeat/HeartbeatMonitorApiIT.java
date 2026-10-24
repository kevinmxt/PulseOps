package dev.pulseops.heartbeat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HeartbeatMonitorApiIT {

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
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        monitorRepository.deleteAll();
    }

    @Test
    void createsHeartbeatMonitorAndReturnsTokenOnce() throws Exception {
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nightly","type":"HEARTBEAT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("nightly"))
                .andExpect(jsonPath("$.type").value("HEARTBEAT"))
                .andExpect(jsonPath("$.intervalSeconds").value(300))
                .andExpect(jsonPath("$.heartbeatGraceSeconds").value(60))
                .andExpect(jsonPath("$.currentStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.targetUrl").value(nullValue()))
                .andExpect(jsonPath("$.method").value(nullValue()))
                .andExpect(jsonPath("$.connectTimeoutMs").value(nullValue()))
                .andExpect(jsonPath("$.responseTimeoutMs").value(nullValue()))
                .andExpect(jsonPath("$.expectedStatusMin").value(nullValue()))
                .andExpect(jsonPath("$.expectedStatusMax").value(nullValue()))
                .andExpect(jsonPath("$.failureThreshold").value(nullValue()))
                .andExpect(jsonPath("$.recoveryThreshold").value(nullValue()))
                .andExpect(jsonPath("$.lastHeartbeatAt").value(nullValue()))
                .andExpect(jsonPath("$.heartbeatToken").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        long id = json.get("id").asLong();
        String token = json.get("heartbeatToken").asText();
        assertThat(token).hasSize(43);

        Map<String, Object> credential = jdbcTemplate.queryForMap(
                "select token_hash, revoked_at from heartbeat_credential where monitor_id = ?", id);
        assertThat(credential.get("token_hash")).isEqualTo(sha256Hex(token));
        assertThat(credential.get("token_hash")).isNotEqualTo(token);
        assertThat(credential.get("revoked_at")).isNull();
    }

    @Test
    void neverReturnsTokenOnListOrDetail() throws Exception {
        long id = createHeartbeat("nightly", null, null);

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heartbeatToken").value(nullValue()));

        mockMvc.perform(get("/api/v1/monitors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].heartbeatToken").value(nullValue()))
                .andExpect(jsonPath("$.items[0].type").value("HEARTBEAT"));
    }

    @Test
    void ignoresHttpOnlyFieldsAndThresholdsOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"ignored",
                                  "type":"HEARTBEAT",
                                  "targetUrl":"not a url",
                                  "method":"POST",
                                  "connectTimeoutMs":1,
                                  "responseTimeoutMs":1,
                                  "expectedStatusMin":999,
                                  "expectedStatusMax":1,
                                  "failureThreshold":0,
                                  "recoveryThreshold":99
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("HEARTBEAT"))
                .andExpect(jsonPath("$.targetUrl").value(nullValue()))
                .andExpect(jsonPath("$.method").value(nullValue()))
                .andExpect(jsonPath("$.failureThreshold").value(nullValue()))
                .andExpect(jsonPath("$.recoveryThreshold").value(nullValue()));
    }

    @Test
    void validatesHeartbeatIntervalAndGraceBoundaries() throws Exception {
        assertCreateRejected("""
                {"name":"hb","type":"HEARTBEAT","intervalSeconds":29}
                """);
        assertCreateRejected("""
                {"name":"hb","type":"HEARTBEAT","intervalSeconds":2592001}
                """);
        assertCreateRejected("""
                {"name":"hb","type":"HEARTBEAT","heartbeatGraceSeconds":-1}
                """);
        assertCreateRejected("""
                {"name":"hb","type":"HEARTBEAT","heartbeatGraceSeconds":86401}
                """);
        assertCreateRejected("""
                {"name":" ","type":"HEARTBEAT"}
                """);
        assertCreateRejected("""
                {"name":"hb","type":"UNKNOWN"}
                """);

        long min = createHeartbeat("min", 30, 0);
        mockMvc.perform(get("/api/v1/monitors/{id}", min))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(30))
                .andExpect(jsonPath("$.heartbeatGraceSeconds").value(0));

        long max = createHeartbeat("max", 2592000, 86400);
        mockMvc.perform(get("/api/v1/monitors/{id}", max))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalSeconds").value(2592000))
                .andExpect(jsonPath("$.heartbeatGraceSeconds").value(86400));
    }

    @Test
    void updatesHeartbeatConfigAndRecomputesDeadline() throws Exception {
        long id = createHeartbeat("nightly", 300, 60);
        MonitorEntity created = monitorRepository.findById(id).orElseThrow();
        assertThat(created.getNextCheckAt())
                .isEqualTo(created.getCreatedAt().plusSeconds(300 + 60));

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nightly updated","intervalSeconds":600,"heartbeatGraceSeconds":120,
                                 "targetUrl":"not a url","failureThreshold":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("nightly updated"))
                .andExpect(jsonPath("$.type").value("HEARTBEAT"))
                .andExpect(jsonPath("$.intervalSeconds").value(600))
                .andExpect(jsonPath("$.heartbeatGraceSeconds").value(120))
                .andExpect(jsonPath("$.heartbeatToken").value(nullValue()))
                .andExpect(jsonPath("$.targetUrl").value(nullValue()))
                .andExpect(jsonPath("$.failureThreshold").value(nullValue()));

        MonitorEntity updated = monitorRepository.findById(id).orElseThrow();
        assertThat(updated.getNextCheckAt())
                .isEqualTo(updated.getCreatedAt().plusSeconds(600 + 120));
    }

    @Test
    void recomputesDeadlineFromLastHeartbeat() throws Exception {
        long id = createHeartbeat("nightly", 300, 60);
        Instant lastHeartbeat = Instant.parse("2026-10-24T00:00:00Z");
        jdbcTemplate.update("update monitor set last_heartbeat_at = ? where id = ?",
                Timestamp.from(lastHeartbeat), id);

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"nightly","intervalSeconds":600,"heartbeatGraceSeconds":120}
                                """))
                .andExpect(status().isOk());

        MonitorEntity updated = monitorRepository.findById(id).orElseThrow();
        assertThat(updated.getNextCheckAt()).isEqualTo(lastHeartbeat.plusSeconds(600 + 120));
    }

    @Test
    void rejectsTypeChanges() throws Exception {
        long heartbeatId = createHeartbeat("hb", null, null);

        mockMvc.perform(put("/api/v1/monitors/{id}", heartbeatId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"hb","type":"HTTP","targetUrl":"http://8.8.8.8/health"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        long httpId = createHttp("http");
        mockMvc.perform(put("/api/v1/monitors/{id}", httpId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"http","type":"HEARTBEAT"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void cascadesCredentialDeletionWithMonitor() throws Exception {
        long id = createHeartbeat("nightly", null, null);
        assertThat(credentialCount(id)).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/monitors/{id}", id))
                .andExpect(status().isNoContent());

        assertThat(credentialCount(id)).isZero();
    }

    @Test
    void excludesHeartbeatMonitorsFromProbeSchedule() throws Exception {
        long heartbeatId = createHeartbeat("hb", null, null);
        long httpId = createHttp("http");
        Timestamp past = Timestamp.from(Instant.now().minusSeconds(60));
        jdbcTemplate.update("update monitor set next_check_at = ? where id in (?, ?)", past, heartbeatId, httpId);

        List<Long> due = monitorRepository.findDueMonitorIds(Instant.now(), PageRequest.of(0, 100));

        assertThat(due).contains(httpId).doesNotContain(heartbeatId);
    }

    private void assertCreateRejected(String body) throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private long createHeartbeat(String name, Integer interval, Integer grace) throws Exception {
        String body = """
                {"name":"%s","type":"HEARTBEAT"%s%s}
                """.formatted(name,
                interval == null ? "" : ",\"intervalSeconds\":" + interval,
                grace == null ? "" : ",\"heartbeatGraceSeconds\":" + grace);
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createHttp(String name) throws Exception {
        String body = """
                {"name":"%s","targetUrl":"http://8.8.8.8/health"}
                """.formatted(name);
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private int credentialCount(long monitorId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from heartbeat_credential where monitor_id = ?", Integer.class, monitorId);
        return count == null ? 0 : count;
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
