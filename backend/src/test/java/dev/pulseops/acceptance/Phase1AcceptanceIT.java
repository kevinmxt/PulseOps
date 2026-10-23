package dev.pulseops.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.probe.ProbeScheduler;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.probe.batch-size=50"
})
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(Phase1AcceptanceIT.AcceptanceTestConfiguration.class)
class Phase1AcceptanceIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;
    private static final AtomicInteger okHits = new AtomicInteger();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ProbeScheduler probeScheduler;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    @Qualifier("probeExecutor")
    ThreadPoolTaskExecutor probeExecutor;

    @TestConfiguration(proxyBeanMethods = false)
    static class AcceptanceTestConfiguration {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }

        @Bean
        @Primary
        DnsResolver loopbackDnsResolver() {
            return new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    return new InetAddress[]{InetAddress.getByName("127.0.0.1")};
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            };
        }

        @Bean
        @Primary
        dev.pulseops.monitor.target.TargetPolicy permissiveTargetPolicy() {
            return target -> {
            };
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            respond(exchange, 200, "ok");
        });
        server.createContext("/fail", exchange -> respond(exchange, 503, "SECRET_RESPONSE_BODY"));
        server.createContext("/slow", exchange -> {
            sleep(2500);
            respond(exchange, 200, "slow");
        });
        server.createContext("/secret", exchange -> respond(exchange, 503, "SECRET_RESPONSE_BODY"));
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @BeforeEach
    void resetState() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        CLOCK.reset();
        okHits.set(0);
    }

    @AfterEach
    void waitForProbesToFinish() {
        awaitUntil(Duration.ofSeconds(5), () -> {
            assertThat(probeExecutor.getThreadPoolExecutor().getActiveCount()).isZero();
            assertThat(probeExecutor.getThreadPoolExecutor().getQueue()).isEmpty();
        });
    }

    @Test
    void probeSucceedsEndToEndAndExposesStatusAndHistory() throws Exception {
        long id = createMonitor("e2e-ok", "/ok", 60, 2000, true);

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(1));

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("UP"))
                .andExpect(jsonPath("$.lastCheckedAt").value("2026-10-21T08:00:00Z"))
                .andExpect(jsonPath("$.lastSuccessAt").value("2026-10-21T08:00:00Z"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].success").value(true))
                .andExpect(jsonPath("$.items[0].failureType").doesNotExist())
                .andExpect(jsonPath("$.items[0].httpStatusCode").value(200))
                .andExpect(jsonPath("$.items[0].errorSummary").doesNotExist())
                .andExpect(jsonPath("$.items[0].startedAt").value("2026-10-21T08:00:00Z"));
    }

    @Test
    void probeFailureEndToEndIsClassifiedWithoutLeakingResponseBody() throws Exception {
        long id = createMonitor("e2e-fail", "/fail", 60, 2000, true);

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(1));

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("DOWN"))
                .andExpect(jsonPath("$.lastSuccessAt").doesNotExist());

        String history = mockMvc.perform(get("/api/v1/monitors/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].success").value(false))
                .andExpect(jsonPath("$.items[0].failureType").value("UNEXPECTED_STATUS"))
                .andExpect(jsonPath("$.items[0].httpStatusCode").value(503))
                .andReturn().getResponse().getContentAsString();

        assertThat(history)
                .doesNotContain("SECRET_RESPONSE_BODY")
                .doesNotContain("http://127.0.0.1");
    }

    @Test
    void probeTimeoutEndToEndIsClassifiedAsResponseTimeout() throws Exception {
        long id = createMonitor("e2e-timeout", "/slow", 60, 1000, true);

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(8), () ->
                assertThat(checkResultRepository.count()).isEqualTo(1));

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("DOWN"));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].success").value(false))
                .andExpect(jsonPath("$.items[0].failureType").value("RESPONSE_TIMEOUT"))
                .andExpect(jsonPath("$.items[0].httpStatusCode").doesNotExist());
    }

    @Test
    void disabledMonitorProducesNoCheckResultsAcrossTicksAndResumesWhenReenabled() throws Exception {
        long id = createMonitor("e2e-disabled", "/ok", 60, 2000, false);

        for (int tick = 0; tick < 3; tick++) {
            probeScheduler.tick();
            CLOCK.advance(Duration.ofSeconds(60));
        }
        sleep(300);
        assertThat(checkResultRepository.count()).isZero();
        assertThat(okHits).hasValue(0);

        updateEnabled(id, "e2e-disabled", "/ok", 60, 2000, true);
        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(1));

        mockMvc.perform(get("/api/v1/monitors/{id}/checks", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].success").value(true));
    }

    @Test
    void logsAndCheckResultResponsesDoNotLeakTargetOrResponseBody(CapturedOutput output) throws Exception {
        String marker = "credential-" + UUID.randomUUID();
        long id = createMonitorAtPath("e2e-secret", "/secret/" + marker, 60, 2000, true);

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(1));

        String history = mockMvc.perform(get("/api/v1/monitors/{id}/checks", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(history)
                .doesNotContain(marker)
                .doesNotContain("SECRET_RESPONSE_BODY")
                .doesNotContain("Cookie")
                .doesNotContain("Authorization");

        mockMvc.perform(get("/api/v1/monitors/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetUrl").value("http://127.0.0.1:" + port + "/secret/" + marker));

        assertThat(output)
                .doesNotContain(marker)
                .doesNotContain("SECRET_RESPONSE_BODY");
    }

    private long createMonitor(String name, String path, int intervalSeconds, int responseTimeoutMs,
                               boolean enabled) throws Exception {
        return createMonitorAtPath(name, path, intervalSeconds, responseTimeoutMs, enabled);
    }

    private long createMonitorAtPath(String name, String path, int intervalSeconds,
                                     int responseTimeoutMs, boolean enabled) throws Exception {
        String body = mockMvc.perform(post("/api/v1/monitors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(name, path, intervalSeconds, responseTimeoutMs, enabled)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return json.get("id").asLong();
    }

    private void updateEnabled(long id, String name, String path, int intervalSeconds,
                               int responseTimeoutMs, boolean enabled) throws Exception {
        mockMvc.perform(put("/api/v1/monitors/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configJson(name, path, intervalSeconds, responseTimeoutMs, enabled)))
                .andExpect(status().isOk());
    }

    private static String configJson(String name, String path, int intervalSeconds,
                                     int responseTimeoutMs, boolean enabled) {
        return """
                {
                  "name":"%s",
                  "targetUrl":"http://127.0.0.1:%d%s",
                  "method":"GET",
                  "intervalSeconds":%d,
                  "connectTimeoutMs":1000,
                  "responseTimeoutMs":%d,
                  "expectedStatusMin":200,
                  "expectedStatusMax":299,
                  "enabled":%s,
                  "failureThreshold":1
                }
                """.formatted(name, port, path, intervalSeconds, responseTimeoutMs, enabled);
    }

    private static void awaitUntil(Duration timeout, Runnable assertion) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError failure) {
                if (System.nanoTime() >= deadline) {
                    throw failure;
                }
                sleep(25);
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may close the connection early; tests assert the probe outcome.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static final class MutableClock extends Clock {

        private volatile Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void reset() {
            current = BASE_TIME;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
