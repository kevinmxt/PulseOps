package dev.pulseops.heartbeat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.probe.CheckResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "pulseops.heartbeat.require-https=true")
@AutoConfigureMockMvc
@Testcontainers
class HeartbeatHttpsRequiredIT {

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

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void rejectsPlainHttpReports() throws Exception {
        String token = createHeartbeat();

        mockMvc.perform(post("/api/v1/heartbeats/{token}", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("HTTPS_REQUIRED"));
        assertThat(monitorRepository.findAll().getFirst().getLastHeartbeatAt()).isNull();
        assertThat(checkResultRepository.count()).isZero();
    }

    @Test
    void acceptsForwardedHttpsAndNativeTls() throws Exception {
        String token = createHeartbeat();

        mockMvc.perform(post("/api/v1/heartbeats/{token}", token)
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/heartbeats/{token}", token).secure(true))
                .andExpect(status().isNoContent());

        assertThat(checkResultRepository.count()).isEqualTo(2);
    }

    private String createHeartbeat() throws Exception {
        String response = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"tls","type":"HEARTBEAT"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("heartbeatToken").asText();
    }
}
