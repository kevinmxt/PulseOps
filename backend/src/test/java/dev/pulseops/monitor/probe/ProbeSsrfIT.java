package dev.pulseops.monitor.probe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProbeSsrfIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger hits = new AtomicInteger();

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 200, "reachable");
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        hits.set(0);
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
            "http://[fe80::1]/health",
            "http://192.0.2.1/health",
            "http://198.18.0.1/health",
            "http://198.51.100.1/health",
            "http://203.0.113.1/health",
            "http://[2001:db8::1]/health",
            "http://[2001::1]/health",
            "http://[2002:7f00:1::]/health",
            "http://[64:ff9b::10.0.0.1]/health",
            "http://[::ffff:10.0.0.1]/health"
    })
    void blocksRestrictedTargetAtExecutionTime(String targetUrl) {
        MonitorEntity monitor = saveMonitor(targetUrl);

        probeService.probe(monitor.getId());

        CheckResultEntity result = checkResultRepository.findAll().getFirst();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.CONNECTION_FAILED);
        assertThat(result.getHttpStatusCode()).isNull();
        assertThat(result.getErrorSummary()).contains("UnknownHostException");

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void blocksReachableLoopbackEndpointWithoutConnecting() {
        MonitorEntity monitor = saveMonitor("http://127.0.0.1:" + port + "/health");

        probeService.probe(monitor.getId());

        CheckResultEntity result = checkResultRepository.findAll().getFirst();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.CONNECTION_FAILED);
        assertThat(result.getErrorSummary()).contains("UnknownHostException");
        assertThat(hits).hasValue(0);
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // The probe may close the connection early; tests assert the probe outcome.
        }
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
        monitor.setFailureThreshold(1);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitorRepository.save(monitor);
    }
}
