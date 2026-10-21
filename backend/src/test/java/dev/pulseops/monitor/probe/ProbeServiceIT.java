package dev.pulseops.monitor.probe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.monitor.MonitorDisabledException;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@Import(ProbeServiceIT.LoopbackDnsConfiguration.class)
class ProbeServiceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;

    private static final AtomicInteger okHits = new AtomicInteger();
    private static final AtomicReference<String> inspectMethod = new AtomicReference<>();
    private static final AtomicReference<String> inspectCookie = new AtomicReference<>();
    private static final AtomicReference<Integer> inspectBodyLength = new AtomicReference<>();

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class LoopbackDnsConfiguration {

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
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            respond(exchange, 200, "ok");
        });
        server.createContext("/fail", exchange -> respond(exchange, 503, "SECRET_RESPONSE_BODY"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/ok");
            respond(exchange, 302, "");
        });
        server.createContext("/slow", exchange -> {
            sleep(2500);
            respond(exchange, 200, "slow");
        });
        server.createContext("/slow-body", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            OutputStream body = exchange.getResponseBody();
            body.flush();
            sleep(2500);
            try {
                body.write(new byte[1000]);
            } catch (IOException ignored) {
                // The probe closes the connection without reading the body.
            } finally {
                exchange.close();
            }
        });
        server.createContext("/inspect", exchange -> {
            inspectMethod.set(exchange.getRequestMethod());
            inspectCookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            inspectBodyLength.set(exchange.getRequestBody().readAllBytes().length);
            respond(exchange, 200, "ok");
        });
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
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        okHits.set(0);
        inspectMethod.set(null);
        inspectCookie.set(null);
        inspectBodyLength.set(null);
    }

    @Test
    void recordsSuccessAndUpdatesMonitorStatus() {
        MonitorEntity monitor = saveMonitorForPath("/ok", 200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        CheckResultEntity result = onlyResult();
        assertThat(result.getMonitorId()).isEqualTo(monitor.getId());
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFailureType()).isNull();
        assertThat(result.getHttpStatusCode()).isEqualTo(200);
        assertThat(result.getErrorSummary()).isNull();
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getFinishedAt()).isAfterOrEqualTo(result.getStartedAt());
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(updated.getLastCheckedAt()).isEqualTo(result.getFinishedAt());
        assertThat(updated.getLastSuccessAt()).isEqualTo(result.getFinishedAt());
        assertThat(updated.getNextCheckAt())
                .isEqualTo(result.getFinishedAt().plusSeconds(updated.getIntervalSeconds()));
        assertConfigUnchanged(monitor, updated);
    }

    @Test
    void connectsUsingAddressReturnedByDnsResolver() {
        MonitorEntity monitor = saveMonitorForUrl("http://pulseops-probe.test:" + port + "/ok",
                200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        assertThat(onlyResult().isSuccess()).isTrue();
        assertThat(okHits).hasValue(1);
    }

    @Test
    void recordsUnexpectedStatusAndDoesNotLeakResponseBody() {
        MonitorEntity monitor = saveMonitorForPath("/fail", 200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        CheckResultEntity result = onlyResult();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.UNEXPECTED_STATUS);
        assertThat(result.getHttpStatusCode()).isEqualTo(503);
        assertThat(result.getErrorSummary())
                .contains("503")
                .doesNotContain("SECRET_RESPONSE_BODY")
                .hasSizeLessThanOrEqualTo(500);

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getLastCheckedAt()).isEqualTo(result.getFinishedAt());
        assertThat(updated.getLastSuccessAt()).isNull();
        assertThat(updated.getNextCheckAt())
                .isEqualTo(result.getFinishedAt().plusSeconds(updated.getIntervalSeconds()));
        assertConfigUnchanged(monitor, updated);
    }

    @Test
    void doesNotFollowRedirects() {
        MonitorEntity monitor = saveMonitorForPath("/redirect", 200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        CheckResultEntity result = onlyResult();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.UNEXPECTED_STATUS);
        assertThat(result.getHttpStatusCode()).isEqualTo(302);
        assertThat(okHits).hasValue(0);
    }

    @Test
    void classifiesResponseTimeout() {
        MonitorEntity monitor = saveMonitorForPath("/slow", 200, 299, 1000, 1000);

        probeService.probe(monitor.getId());

        CheckResultEntity result = onlyResult();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.RESPONSE_TIMEOUT);
        assertThat(result.getHttpStatusCode()).isNull();
        assertThat(result.getErrorSummary()).contains("timed out");

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void classifiesConnectionRefused() {
        int closedPort = findClosedPort();
        MonitorEntity monitor = saveMonitorForUrl("http://127.0.0.1:" + closedPort + "/health",
                200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        CheckResultEntity result = onlyResult();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.CONNECTION_FAILED);
        assertThat(result.getHttpStatusCode()).isNull();
        assertThat(result.getErrorSummary()).isNotBlank();

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void doesNotReadResponseBody() {
        MonitorEntity monitor = saveMonitorForPath("/slow-body", 200, 299, 1000, 1000);

        long startedNanos = System.nanoTime();
        probeService.probe(monitor.getId());
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);

        CheckResultEntity result = onlyResult();
        assertThat(result.isSuccess())
                .as("failureType=%s, httpStatus=%s, summary=%s",
                        result.getFailureType(), result.getHttpStatusCode(), result.getErrorSummary())
                .isTrue();
        assertThat(result.getHttpStatusCode()).isEqualTo(200);
        assertThat(elapsed).isLessThan(Duration.ofMillis(2000));
    }

    @Test
    void keepsLastSuccessAtWhenFailureFollowsSuccess() {
        MonitorEntity monitor = saveMonitorForPath("/ok", 200, 299, 1000, 2000);
        probeService.probe(monitor.getId());
        CheckResultEntity success = onlyResult();
        assertThat(monitorRepository.findById(monitor.getId()).orElseThrow().getLastSuccessAt())
                .isEqualTo(success.getFinishedAt());

        MonitorEntity retargeted = monitorRepository.findById(monitor.getId()).orElseThrow();
        retargeted.setTargetUrl("http://127.0.0.1:" + port + "/fail");
        monitorRepository.save(retargeted);
        probeService.probe(monitor.getId());

        List<CheckResultEntity> results = checkResultRepository.findAll();
        assertThat(results).hasSize(2);
        CheckResultEntity failure = results.stream()
                .filter(result -> !result.isSuccess())
                .findFirst()
                .orElseThrow();
        assertThat(failure.getFailureType()).isEqualTo(CheckFailureType.UNEXPECTED_STATUS);

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getLastCheckedAt()).isEqualTo(failure.getFinishedAt());
        assertThat(updated.getLastSuccessAt()).isEqualTo(success.getFinishedAt());
    }

    @Test
    void refusesToProbeDisabledMonitor() {
        MonitorEntity monitor = saveMonitorForPath("/ok", 200, 299, 1000, 2000);
        monitor.setEnabled(false);
        monitorRepository.save(monitor);

        assertThatThrownBy(() -> probeService.probe(monitor.getId()))
                .isInstanceOf(MonitorDisabledException.class);

        assertThat(checkResultRepository.count()).isZero();
        assertThat(okHits).hasValue(0);
    }

    @Test
    void ignoresSystemProxySettings() {
        String previousHost = System.getProperty("http.proxyHost");
        String previousPort = System.getProperty("http.proxyPort");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", String.valueOf(findClosedPort()));
        try {
            MonitorEntity monitor = saveMonitorForPath("/ok", 200, 299, 1000, 2000);

            probeService.probe(monitor.getId());

            assertThat(onlyResult().isSuccess()).isTrue();
            assertThat(okHits).hasValue(1);
        } finally {
            restoreProperty("http.proxyHost", previousHost);
            restoreProperty("http.proxyPort", previousPort);
        }
    }

    @Test
    void sendsNoBodyAndNoCookies() {
        MonitorEntity monitor = saveMonitorForPath("/inspect", 200, 299, 1000, 2000);

        probeService.probe(monitor.getId());

        assertThat(onlyResult().isSuccess()).isTrue();
        assertThat(inspectMethod).hasValue("GET");
        assertThat(inspectCookie).hasValue(null);
        assertThat(inspectBodyLength).hasValue(0);
    }

    private CheckResultEntity onlyResult() {
        List<CheckResultEntity> results = checkResultRepository.findAll();
        assertThat(results).hasSize(1);
        return results.getFirst();
    }

    private MonitorEntity saveMonitorForPath(String path, int expectedMin, int expectedMax,
                                             int connectTimeoutMs, int responseTimeoutMs) {
        return saveMonitorForUrl("http://127.0.0.1:" + port + path, expectedMin, expectedMax,
                connectTimeoutMs, responseTimeoutMs);
    }

    private MonitorEntity saveMonitorForUrl(String targetUrl, int expectedMin, int expectedMax,
                                            int connectTimeoutMs, int responseTimeoutMs) {
        Instant now = Instant.parse("2026-10-21T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("probe test");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl(targetUrl);
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(connectTimeoutMs);
        monitor.setResponseTimeoutMs(responseTimeoutMs);
        monitor.setExpectedStatusMin(expectedMin);
        monitor.setExpectedStatusMax(expectedMax);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitorRepository.save(monitor);
    }

    private static void assertConfigUnchanged(MonitorEntity before, MonitorEntity after) {
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getTargetUrl()).isEqualTo(before.getTargetUrl());
        assertThat(after.getMethod()).isEqualTo(before.getMethod());
        assertThat(after.getIntervalSeconds()).isEqualTo(before.getIntervalSeconds());
        assertThat(after.getConnectTimeoutMs()).isEqualTo(before.getConnectTimeoutMs());
        assertThat(after.getResponseTimeoutMs()).isEqualTo(before.getResponseTimeoutMs());
        assertThat(after.getExpectedStatusMin()).isEqualTo(before.getExpectedStatusMin());
        assertThat(after.getExpectedStatusMax()).isEqualTo(before.getExpectedStatusMax());
        assertThat(after.isEnabled()).isEqualTo(before.isEnabled());
        assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static int findClosedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
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
            // Client may close the connection early; tests assert the probe outcome.
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
