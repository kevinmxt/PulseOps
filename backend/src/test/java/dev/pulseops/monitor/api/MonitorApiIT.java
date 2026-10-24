package dev.pulseops.monitor.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MonitorApiIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MonitorRepository repository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    Environment environment;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsMonitorWithDefaults() throws Exception {
        String request = """
                {"name":"My API","targetUrl":"http://8.8.8.8/health"}
                """;

        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("My API"))
                .andExpect(jsonPath("$.type").value("HTTP"))
                .andExpect(jsonPath("$.targetUrl").value("http://8.8.8.8/health"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.connectTimeoutMs").value(3000))
                .andExpect(jsonPath("$.responseTimeoutMs").value(10000))
                .andExpect(jsonPath("$.expectedStatusMin").value(200))
                .andExpect(jsonPath("$.expectedStatusMax").value(299))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.currentStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.lastCheckedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.nextCheckAt").doesNotExist());
    }

    @Test
    void listsMonitorsNewestFirstWithPagination() throws Exception {
        create("first");
        create("second");
        create("third");

        mockMvc.perform(get("/api/v1/monitors").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("third"))
                .andExpect(jsonPath("$.items[1].name").value("second"));

        mockMvc.perform(get("/api/v1/monitors").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("first"));
    }

    @Test
    void getsDetailAndReplacesAllFieldsOnUpdate() throws Exception {
        long id = create("original");

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("original"))
                .andExpect(jsonPath("$.method").value("GET"));

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"updated","targetUrl":"http://1.1.1.1/health"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("updated"))
                .andExpect(jsonPath("$.targetUrl").value("http://1.1.1.1/health"))
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.intervalSeconds").value(60))
                .andExpect(jsonPath("$.connectTimeoutMs").value(3000))
                .andExpect(jsonPath("$.responseTimeoutMs").value(10000))
                .andExpect(jsonPath("$.expectedStatusMin").value(200))
                .andExpect(jsonPath("$.expectedStatusMax").value(299));
    }

    @Test
    void deletesMonitorAndThenReturnsNotFound() throws Exception {
        long id = create("to delete");

        mockMvc.perform(delete("/api/v1/monitors/{id}", id))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MONITOR_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(delete("/api/v1/monitors/{id}", id))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsInvalidConfigWithStableErrorEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":" ","targetUrl":"http://8.8.8.8/health"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("name")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"API","targetUrl":"ftp://8.8.8.8/health"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_URL"));
    }

    @Test
    void createsMonitorWithCustomConfiguration() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"custom",
                                  "targetUrl":"https://1.1.1.1/health",
                                  "method":"HEAD",
                                  "intervalSeconds":120,
                                  "connectTimeoutMs":5000,
                                  "responseTimeoutMs":20000,
                                  "expectedStatusMin":200,
                                  "expectedStatusMax":399,
                                  "enabled":false
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("HEAD"))
                .andExpect(jsonPath("$.intervalSeconds").value(120))
                .andExpect(jsonPath("$.connectTimeoutMs").value(5000))
                .andExpect(jsonPath("$.responseTimeoutMs").value(20000))
                .andExpect(jsonPath("$.expectedStatusMin").value(200))
                .andExpect(jsonPath("$.expectedStatusMax").value(399))
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void defaultsToLoopbackAddressUnlessOverridden() {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/health",
            "http://localhost/health",
            "http://10.0.0.1/health",
            "http://172.16.0.1/health",
            "http://192.168.1.1/health",
            "http://100.64.0.1/health",
            "http://169.254.1.1/health",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/health",
            "http://[fe80::1]/health"
    })
    void rejectsRestrictedTargetAddresses(String targetUrl) throws Exception {
        long before = repository.count();

        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"blocked","targetUrl":"%s"}
                                """.formatted(targetUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_URL"));

        assertThat(repository.count()).isEqualTo(before);
    }

    @Test
    void blockedTargetErrorDoesNotEchoRequestedUrl() throws Exception {
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"blocked","targetUrl":"http://127.0.0.1/secret-path-marker"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_URL"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .doesNotContain("secret-path-marker")
                .doesNotContain("http://127.0.0.1");
    }

    @Test
    void allowsSavingWhenTargetDnsResolutionFails() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"unresolvable","targetUrl":"http://does-not-exist.invalid/health"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void schedulesImmediateCheckOnCreateReenableAndIntervalChange() throws Exception {
        long id = create("scheduled");

        MonitorEntity created = repository.findById(id).orElseThrow();
        assertThat(created.getNextCheckAt()).isNotNull();
        assertThat(created.getNextCheckAt()).isEqualTo(created.getCreatedAt());

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"scheduled","targetUrl":"http://8.8.8.8/health","enabled":false}
                                """))
                .andExpect(status().isOk());

        MonitorEntity disabled = repository.findById(id).orElseThrow();
        assertThat(disabled.isEnabled()).isFalse();

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"scheduled","targetUrl":"http://8.8.8.8/health","enabled":true}
                                """))
                .andExpect(status().isOk());

        MonitorEntity reenabled = repository.findById(id).orElseThrow();
        assertThat(reenabled.isEnabled()).isTrue();
        assertThat(reenabled.getNextCheckAt()).isEqualTo(reenabled.getUpdatedAt());

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"scheduled","targetUrl":"http://8.8.8.8/health","intervalSeconds":30}
                                """))
                .andExpect(status().isOk());

        MonitorEntity intervalChanged = repository.findById(id).orElseThrow();
        assertThat(intervalChanged.getIntervalSeconds()).isEqualTo(30);
        assertThat(intervalChanged.getNextCheckAt()).isEqualTo(intervalChanged.getUpdatedAt());
    }

    @Test
    void rejectsRestrictedTargetOnUpdate() throws Exception {
        long id = create("updatable");

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"updatable","targetUrl":"http://127.0.0.1/health"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TARGET_URL"));

        assertThat(repository.findById(id).orElseThrow().getTargetUrl())
                .isEqualTo("http://8.8.8.8/health");
    }

    @Test
    void rejectsInvalidConfigValuesAtHttpLayer() throws Exception {
        String[] invalidBodies = {
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"method\":\"POST\"}",
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"intervalSeconds\":9}",
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"connectTimeoutMs\":999}",
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"responseTimeoutMs\":30001}",
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"expectedStatusMin\":600}",
                "{\"name\":\"API\",\"targetUrl\":\"http://8.8.8.8\",\"expectedStatusMin\":500,\"expectedStatusMax\":400}"
        };

        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/v1/monitors")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void usesDefaultPageSize() throws Exception {
        mockMvc.perform(get("/api/v1/monitors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void returns405ForUnsupportedMethod() throws Exception {
        mockMvc.perform(patch("/api/v1/monitors"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void returns415ForUnsupportedContentType() throws Exception {
        mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void reschedulesWhenTargetUrlChanges() throws Exception {
        long id = create("moving target");

        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"moving target","targetUrl":"http://1.1.1.1/other"}
                                """))
                .andExpect(status().isOk());

        MonitorEntity updated = repository.findById(id).orElseThrow();
        assertThat(updated.getTargetUrl()).isEqualTo("http://1.1.1.1/other");
        assertThat(updated.getNextCheckAt()).isEqualTo(updated.getUpdatedAt());
    }

    @Test
    void rejectsPageSizeAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/v1/monitors").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void returnsLatestCheckDurationForHttpMonitor() throws Exception {
        long id = create("with checks");
        saveCheckResult(id, Instant.parse("2026-10-24T08:00:00Z"), 120);
        saveCheckResult(id, Instant.parse("2026-10-24T08:01:00Z"), 87);

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheckDurationMs").value(87));

        mockMvc.perform(get("/api/v1/monitors").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].lastCheckDurationMs").value(87));
    }

    @Test
    void returnsNullDurationWhenNoChecksExist() throws Exception {
        long id = create("no checks");

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheckDurationMs")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void returnsNullDurationForHeartbeatMonitorEvenWithCheckResult() throws Exception {
        long id = createHeartbeat("heartbeat");
        saveCheckResult(id, Instant.parse("2026-10-24T08:02:00Z"), 0);

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheckDurationMs")
                        .value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/v1/monitors").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].lastCheckDurationMs")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void returnsDurationsForMonitorsAcrossPages() throws Exception {
        long first = create("first");
        long second = create("second");
        long third = create("third");
        saveCheckResult(first, Instant.parse("2026-10-24T08:00:00Z"), 11);
        saveCheckResult(second, Instant.parse("2026-10-24T08:00:00Z"), 22);
        saveCheckResult(third, Instant.parse("2026-10-24T08:00:00Z"), 33);

        mockMvc.perform(get("/api/v1/monitors").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("third"))
                .andExpect(jsonPath("$.items[0].lastCheckDurationMs").value(33))
                .andExpect(jsonPath("$.items[1].name").value("second"))
                .andExpect(jsonPath("$.items[1].lastCheckDurationMs").value(22));

        mockMvc.perform(get("/api/v1/monitors").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("first"))
                .andExpect(jsonPath("$.items[0].lastCheckDurationMs").value(11));
    }

    private long createHeartbeat(String name) throws Exception {
        String body = """
                {"name":"%s","type":"HEARTBEAT","intervalSeconds":300}
                """.formatted(name);
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private void saveCheckResult(long monitorId, Instant startedAt, long durationMs) {
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(startedAt);
        result.setFinishedAt(startedAt.plusMillis(durationMs));
        result.setDurationMs(durationMs);
        result.setSuccess(true);
        checkResultRepository.save(result);
    }

    private long create(String name) throws Exception {
        String body = """
                {"name":"%s","targetUrl":"http://8.8.8.8/health"}
                """.formatted(name);
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asLong();
    }
}
