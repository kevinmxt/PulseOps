package dev.pulseops.monitor.probe;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.target.TargetPolicy;
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
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "pulseops.probe.scheduling-enabled=false")
@Testcontainers
@Import(ThresholdStatusIT.StateMachineTestConfiguration.class)
class ThresholdStatusIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;
    private static volatile boolean failing;

    @Autowired
    MonitorService monitorService;

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class StateMachineTestConfiguration {

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
        TargetPolicy permissiveTargetPolicy() {
            return target -> {
            };
        }
    }

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> respond(exchange, 200, "ok"));
        server.createContext("/fail", exchange -> respond(exchange, 503, "unavailable"));
        server.createContext("/toggle", exchange ->
                respond(exchange, failing ? 503 : 200, failing ? "unavailable" : "ok"));
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
        failing = false;
    }

    @Test
    void staysUnknownWhileFailuresAreBelowTheDefaultThreshold() {
        MonitorEntity monitor = createMonitor("/fail");

        probe(monitor);
        probe(monitor);

        MonitorEntity updated = reload(monitor);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(updated.getConsecutiveFailures()).isEqualTo(2);
        assertThat(updated.getConsecutiveSuccesses()).isZero();
    }

    @Test
    void becomesDownWhenFailuresReachTheDefaultThreshold() {
        MonitorEntity monitor = createMonitor("/fail");

        probe(monitor);
        probe(monitor);
        probe(monitor);

        MonitorEntity updated = reload(monitor);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    void upMonitorStaysUpBelowFailureThresholdAndGoesDownAtThreshold() {
        MonitorEntity monitor = createMonitor("/toggle");
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.UP);

        failing = true;
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.UP);

        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void staysDownUntilSuccessesReachTheDefaultRecoveryThreshold() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);

        failing = false;
        probe(monitor);
        MonitorEntity afterFirstSuccess = reload(monitor);
        assertThat(afterFirstSuccess.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(afterFirstSuccess.getConsecutiveSuccesses()).isEqualTo(1);

        probe(monitor);
        MonitorEntity afterSecondSuccess = reload(monitor);
        assertThat(afterSecondSuccess.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(afterSecondSuccess.getConsecutiveSuccesses()).isEqualTo(2);
    }

    @Test
    void successResetsConsecutiveFailures() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getConsecutiveFailures()).isEqualTo(2);

        failing = false;
        probe(monitor);

        MonitorEntity updated = reload(monitor);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.getConsecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void failureResetsConsecutiveSuccesses() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);

        failing = false;
        probe(monitor);
        assertThat(reload(monitor).getConsecutiveSuccesses()).isEqualTo(1);

        failing = true;
        probe(monitor);

        MonitorEntity updated = reload(monitor);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getConsecutiveSuccesses()).isZero();
        assertThat(updated.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void appliesConfiguredThresholds() {
        MonitorEntity monitor = createMonitor("/toggle", 1, 1);

        failing = true;
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);

        failing = false;
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.UP);
    }

    @Test
    void probeSemanticChangeResetsCountersAndKeepsStatus() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);

        MonitorEntity updated = updateConfig(monitor, 120, true, null, null);

        assertThat(updated.getConfigVersion()).isEqualTo(2);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getConsecutiveFailures()).isZero();
        assertThat(updated.getConsecutiveSuccesses()).isZero();
        assertThat(updated.getNextCheckAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void thresholdChangeDoesNotResetCountersOrScheduleImmediateProbe() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        MonitorEntity beforeUpdate = reload(monitor);
        assertThat(beforeUpdate.getConsecutiveFailures()).isEqualTo(2);
        assertThat(beforeUpdate.getNextCheckAt()).isEqualTo(BASE_TIME.plusSeconds(60));

        MonitorEntity updated = updateConfig(monitor, 60, true, 2, 2);

        assertThat(updated.getConfigVersion()).isEqualTo(1);
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(updated.getConsecutiveFailures()).isEqualTo(2);
        assertThat(updated.getNextCheckAt()).isEqualTo(BASE_TIME.plusSeconds(60));

        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
    }

    @Test
    void disablingKeepsStatusAndEnablingStartsFromUnknown() {
        MonitorEntity monitor = createMonitor("/toggle");
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(reload(monitor).getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);

        MonitorEntity disabled = updateConfig(monitor, 60, false, null, null);
        assertThat(disabled.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(disabled.getConsecutiveFailures()).isZero();
        assertThat(disabled.getConfigVersion()).isEqualTo(1);

        MonitorEntity enabled = updateConfig(monitor, 60, true, null, null);
        assertThat(enabled.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
        assertThat(enabled.getConsecutiveFailures()).isZero();
        assertThat(enabled.getConsecutiveSuccesses()).isZero();
        assertThat(enabled.getNextCheckAt()).isEqualTo(CLOCK.instant());
    }

    private MonitorEntity createMonitor(String path) {
        return createMonitor(path, 3, 2);
    }

    private MonitorEntity createMonitor(String path, int failureThreshold, int recoveryThreshold) {
        return monitorService.create(new MonitorConfigInput(
                "state machine",
                "http://127.0.0.1:" + port + path,
                "GET",
                60,
                3000,
                10000,
                200,
                299,
                true,
                failureThreshold,
                recoveryThreshold));
    }

    private MonitorEntity updateConfig(MonitorEntity monitor, int intervalSeconds, boolean enabled,
                                       Integer failureThreshold, Integer recoveryThreshold) {
        MonitorEntity current = reload(monitor);
        return monitorService.update(current.getId(), new MonitorConfigInput(
                current.getName(),
                current.getTargetUrl(),
                current.getMethod().name(),
                intervalSeconds,
                current.getConnectTimeoutMs(),
                current.getResponseTimeoutMs(),
                current.getExpectedStatusMin(),
                current.getExpectedStatusMax(),
                enabled,
                failureThreshold,
                recoveryThreshold));
    }

    private void probe(MonitorEntity monitor) {
        probeService.probe(monitor.getId());
    }

    private MonitorEntity reload(MonitorEntity monitor) {
        return monitorRepository.findById(monitor.getId()).orElseThrow();
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
