package dev.pulseops.incident;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.probe.ProbeService;
import dev.pulseops.monitor.target.TargetPolicy;
import dev.pulseops.notification.NotificationDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "pulseops.probe.scheduling-enabled=false")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(IncidentLifecycleIT.IncidentTestConfiguration.class)
class IncidentLifecycleIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;
    private static volatile boolean failing;
    private static volatile CountDownLatch blockedRequestsStarted = new CountDownLatch(2);
    private static volatile CountDownLatch releaseBlockedRequests = new CountDownLatch(1);

    @Autowired
    MonitorService monitorService;

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    IncidentRepository incidentRepository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    NotificationDeliveryRepository notificationDeliveryRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class IncidentTestConfiguration {

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
        server.createContext("/toggle", exchange ->
                respond(exchange, failing ? 503 : 200, failing ? "unavailable" : "ok"));
        server.createContext("/block", exchange -> {
            blockedRequestsStarted.countDown();
            awaitQuietly(releaseBlockedRequests);
            respond(exchange, 503, "unavailable");
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
    void resetState() {
        checkResultRepository.deleteAll();
        incidentRepository.deleteAll();
        monitorRepository.deleteAll();
        CLOCK.reset();
        failing = false;
        blockedRequestsStarted = new CountDownLatch(2);
        releaseBlockedRequests = new CountDownLatch(1);
    }

    @Test
    void createsOneOpenIncidentWhenFailuresReachTheThreshold() {
        MonitorEntity monitor = createMonitor();
        failing = true;

        probe(monitor);
        probe(monitor);
        assertThat(incidentRepository.count()).isZero();

        probe(monitor);

        List<IncidentEntity> incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        IncidentEntity incident = incidents.getFirst();
        assertThat(incident.getMonitorId()).isEqualTo(monitor.getId());
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getStartedAt()).isEqualTo(CLOCK.instant());
        assertThat(incident.getResolvedAt()).isNull();
        assertThat(incident.getFailureType()).isEqualTo(CheckFailureType.UNEXPECTED_STATUS);
        assertThat(incident.getSummary())
                .contains("连续 3 次探测失败")
                .contains("非预期状态码")
                .doesNotContain("http://")
                .hasSizeLessThanOrEqualTo(200);
        assertThat(notificationDeliveryRepository.count()).isZero();
    }

    @Test
    void recordsIncidentMetricsAndInfoLogs(CapturedOutput output) {
        double openedBefore = meterRegistry.counter("incident.opened").count();
        double resolvedBefore = meterRegistry.counter("incident.resolved").count();
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        failing = false;
        probe(monitor);
        probe(monitor);

        assertThat(meterRegistry.counter("incident.opened").count()).isEqualTo(openedBefore + 1);
        assertThat(meterRegistry.counter("incident.resolved").count()).isEqualTo(resolvedBefore + 1);
        assertThat(output)
                .contains("Incident opened")
                .contains("Incident resolved");
    }

    @Test
    void doesNotCreateDuplicateIncidentsWhileFailureContinues() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        Instant startedAt = onlyIncident().getStartedAt();

        probe(monitor);
        probe(monitor);

        assertThat(incidentRepository.count()).isEqualTo(1);
        IncidentEntity unchanged = onlyIncident();
        assertThat(unchanged.getStartedAt()).isEqualTo(startedAt);
        assertThat(unchanged.getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    void resolvesIncidentOnlyWhenSuccessesReachRecoveryThreshold() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);

        failing = false;
        probe(monitor);
        assertThat(onlyIncident().getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(onlyIncident().getResolvedAt()).isNull();

        probe(monitor);
        IncidentEntity resolved = onlyIncident();
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void createsNewIncidentAfterThePreviousOneResolves() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);

        failing = false;
        probe(monitor);
        probe(monitor);
        assertThat(onlyIncident().getStatus()).isEqualTo(IncidentStatus.RESOLVED);

        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);

        List<IncidentEntity> incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(incident -> incident.getStatus() == IncidentStatus.OPEN)
                .hasSize(1);
    }

    @Test
    void disablingMonitorResolvesOpenIncidentSilently() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(onlyIncident().getStatus()).isEqualTo(IncidentStatus.OPEN);

        updateEnabled(monitor, false);

        IncidentEntity resolved = onlyIncident();
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isEqualTo(CLOCK.instant());
        assertThat(reload(monitor).getConsecutiveFailures()).isZero();
    }

    @Test
    void deletingMonitorRemovesItsIncidents() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(incidentRepository.count()).isEqualTo(1);

        monitorService.delete(monitor.getId());

        assertThat(incidentRepository.count()).isZero();
    }

    @Test
    void createsIncidentForMonitorAlreadyDownWithoutOpenIncident() {
        MonitorEntity monitor = createMonitor(1);
        MonitorEntity stale = reload(monitor);
        stale.setCurrentStatus(MonitorStatus.DOWN);
        monitorRepository.save(stale);

        failing = true;
        probe(monitor);

        assertThat(onlyIncident().getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    @Test
    void doesNotCreateIncidentForAlreadyDownMonitorBelowFailureThreshold() {
        MonitorEntity monitor = createMonitor();
        MonitorEntity stale = reload(monitor);
        stale.setCurrentStatus(MonitorStatus.DOWN);
        monitorRepository.save(stale);

        failing = true;
        probe(monitor);

        assertThat(incidentRepository.count()).isZero();
        assertThat(checkResultRepository.count()).isEqualTo(1);
        assertThat(reload(monitor).getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    void successfulProbeBelowRecoveryDoesNotCreateIncidentForAlreadyDownMonitor() {
        MonitorEntity monitor = createMonitor();
        MonitorEntity stale = reload(monitor);
        stale.setCurrentStatus(MonitorStatus.DOWN);
        monitorRepository.save(stale);

        failing = false;
        probe(monitor);

        assertThat(incidentRepository.count()).isZero();
        assertThat(checkResultRepository.count()).isEqualTo(1);
        assertThat(reload(monitor).getConsecutiveSuccesses()).isEqualTo(1);
    }

    @Test
    void databaseRejectsTwoOpenIncidentsForTheSameMonitor() {
        MonitorEntity monitor = createMonitor();
        incidentRepository.saveAndFlush(openIncident(monitor.getId(), BASE_TIME));

        assertThatThrownBy(() -> incidentRepository.saveAndFlush(
                openIncident(monitor.getId(), BASE_TIME.plusSeconds(60))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentProbesCreateOnlyOneIncident() throws Exception {
        MonitorEntity monitor = createMonitor("/block", 1);
        ExecutorService probeThreads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = probeThreads.submit(() -> probe(monitor));
            Future<?> second = probeThreads.submit(() -> probe(monitor));
            assertThat(blockedRequestsStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseBlockedRequests.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            releaseBlockedRequests.countDown();
            probeThreads.shutdownNow();
        }

        assertThat(incidentRepository.count()).isEqualTo(1);
        assertThat(onlyIncident().getStatus()).isEqualTo(IncidentStatus.OPEN);
    }

    private MonitorEntity createMonitor() {
        return createMonitor("/toggle", 3);
    }

    private MonitorEntity createMonitor(int failureThreshold) {
        return createMonitor("/toggle", failureThreshold);
    }

    private MonitorEntity createMonitor(String path, int failureThreshold) {
        return monitorService.create(new MonitorConfigInput(
                "incident state machine",
                "http://127.0.0.1:" + port + path,
                "GET",
                60,
                3000,
                10000,
                200,
                299,
                true,
                failureThreshold,
                null));
    }

    private void updateEnabled(MonitorEntity monitor, boolean enabled) {
        MonitorEntity current = reload(monitor);
        monitorService.update(current.getId(), new MonitorConfigInput(
                current.getName(),
                current.getTargetUrl(),
                current.getMethod().name(),
                current.getIntervalSeconds(),
                current.getConnectTimeoutMs(),
                current.getResponseTimeoutMs(),
                current.getExpectedStatusMin(),
                current.getExpectedStatusMax(),
                enabled,
                null,
                null));
    }

    private static IncidentEntity openIncident(Long monitorId, Instant startedAt) {
        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(monitorId);
        incident.setStartedAt(startedAt);
        incident.setStatus(IncidentStatus.OPEN);
        incident.setFailureType(CheckFailureType.CONNECTION_FAILED);
        incident.setSummary("连续 3 次探测失败，最新失败：连接失败");
        return incident;
    }

    private IncidentEntity onlyIncident() {
        List<IncidentEntity> incidents = incidentRepository.findAll();
        assertThat(incidents).hasSize(1);
        return incidents.getFirst();
    }

    private MonitorEntity reload(MonitorEntity monitor) {
        return monitorRepository.findById(monitor.getId()).orElseThrow();
    }

    private void probe(MonitorEntity monitor) {
        probeService.probe(monitor.getId());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
            // The probe may close the connection early; tests assert the outcome.
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
