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
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.probe.batch-size=10"
})
@Testcontainers
@Import(MonitorConfigRaceIT.RaceTestConfiguration.class)
class MonitorConfigRaceIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final AtomicInteger aHits = new AtomicInteger();
    private static final AtomicInteger bHits = new AtomicInteger();

    private static volatile CountDownLatch aStarted = new CountDownLatch(1);
    private static volatile CountDownLatch aRelease = new CountDownLatch(1);

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;

    @Autowired
    MonitorService monitorService;

    @Autowired
    ProbeService probeService;

    @Autowired
    ProbeScheduler probeScheduler;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceTestConfiguration {

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
        server.createContext("/a", exchange -> {
            aHits.incrementAndGet();
            aStarted.countDown();
            awaitQuietly(aRelease);
            respond(exchange, 200, "ok");
        });
        server.createContext("/b", exchange -> {
            bHits.incrementAndGet();
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
        monitorRepository.deleteAll();
        CLOCK.reset();
        aHits.set(0);
        bHits.set(0);
        aStarted = new CountDownLatch(1);
        aRelease = new CountDownLatch(1);
    }

    @AfterEach
    void releaseBlockedRequests() {
        aRelease.countDown();
    }

    @Test
    void incrementsConfigVersionOnlyWhenProbeSemanticsChange() {
        MonitorEntity created = monitorService.create(input("version", "/a", "GET", 60,
                3000, 10000, 200, 299, true));
        assertThat(created.getConfigVersion()).isEqualTo(1);

        MonitorEntity renamed = update(created.getId(), input("renamed", "/a", "GET", 60,
                3000, 10000, 200, 299, true));
        assertThat(renamed.getConfigVersion()).isEqualTo(1);

        MonitorEntity disabled = update(created.getId(), input("renamed", "/a", "GET", 60,
                3000, 10000, 200, 299, false));
        assertThat(disabled.getConfigVersion()).isEqualTo(1);

        MonitorEntity reenabled = update(created.getId(), input("renamed", "/a", "GET", 60,
                3000, 10000, 200, 299, true));
        assertThat(reenabled.getConfigVersion()).isEqualTo(1);

        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 60,
                3000, 10000, 200, 299, true)).getConfigVersion()).isEqualTo(2);
        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 60,
                3000, 10000, 201, 299, true)).getConfigVersion()).isEqualTo(3);
        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 60,
                3000, 10000, 201, 304, true)).getConfigVersion()).isEqualTo(4);
        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 60,
                4000, 10000, 201, 304, true)).getConfigVersion()).isEqualTo(5);
        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 60,
                4000, 12000, 201, 304, true)).getConfigVersion()).isEqualTo(6);
        assertThat(update(created.getId(), input("renamed", "/a", "HEAD", 120,
                4000, 12000, 201, 304, true)).getConfigVersion()).isEqualTo(7);
        assertThat(update(created.getId(), input("renamed", "/b", "HEAD", 120,
                4000, 12000, 201, 304, true)).getConfigVersion()).isEqualTo(8);

        assertThat(monitorService.get(created.getId()).getConfigVersion()).isEqualTo(8);
    }

    @Test
    void discardsInFlightResultAndDoesNotDelayProbeOfNewTarget() throws Exception {
        MonitorEntity monitor = monitorService.create(input("race", "/a", "GET", 60,
                3000, 10000, 200, 299, true));
        assertThat(monitor.getConfigVersion()).isEqualTo(1);

        ExecutorService staleProbeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CheckResultEntity>> staleProbe =
                    staleProbeThread.submit(() -> probeService.probe(monitor.getId()));
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            MonitorEntity updated = monitorService.update(monitor.getId(), input("race", "/b", "GET", 60,
                    3000, 10000, 200, 299, true));
            assertThat(updated.getConfigVersion()).isEqualTo(2);
            Instant expectedNextCheck = updated.getNextCheckAt();
            assertThat(expectedNextCheck).isEqualTo(BASE_TIME);

            aRelease.countDown();

            assertThat(staleProbe.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(checkResultRepository.count()).isZero();
            MonitorEntity afterStale = monitorService.get(monitor.getId());
            assertThat(afterStale.getConfigVersion()).isEqualTo(2);
            assertThat(afterStale.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
            assertThat(afterStale.getLastCheckedAt()).isNull();
            assertThat(afterStale.getNextCheckAt()).isEqualTo(expectedNextCheck);
            assertThat(aHits).hasValue(1);
            assertThat(bHits).hasValue(0);

            probeScheduler.tick();

            awaitUntil(Duration.ofSeconds(5), () -> {
                List<CheckResultEntity> results = checkResultRepository.findAll();
                assertThat(results).hasSize(1);
                CheckResultEntity result = results.getFirst();
                assertThat(result.getMonitorId()).isEqualTo(monitor.getId());
                assertThat(result.getFailureType()).isEqualTo(CheckFailureType.UNEXPECTED_STATUS);
                MonitorEntity probed = monitorService.get(monitor.getId());
                assertThat(probed.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
                assertThat(probed.getLastCheckedAt()).isEqualTo(result.getFinishedAt());
                assertThat(probed.getNextCheckAt())
                        .isEqualTo(result.getFinishedAt().plusSeconds(probed.getIntervalSeconds()));
            });
            assertThat(bHits).hasValue(1);
        } finally {
            staleProbeThread.shutdownNow();
        }
    }

    @Test
    void discardsInFlightResultWhenIntervalChanges() throws Exception {
        MonitorEntity monitor = monitorService.create(input("interval-change", "/a", "GET", 60,
                3000, 10000, 200, 299, true));

        ExecutorService staleProbeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CheckResultEntity>> staleProbe =
                    staleProbeThread.submit(() -> probeService.probe(monitor.getId()));
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            MonitorEntity updated = monitorService.update(monitor.getId(),
                    input("interval-change", "/a", "GET", 120, 3000, 10000, 200, 299, true));
            assertThat(updated.getConfigVersion()).isEqualTo(2);
            assertThat(updated.getNextCheckAt()).isEqualTo(BASE_TIME);

            aRelease.countDown();

            assertThat(staleProbe.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(checkResultRepository.count()).isZero();
            assertThat(monitorService.get(monitor.getId()).getNextCheckAt()).isEqualTo(BASE_TIME);

            probeScheduler.tick();

            awaitUntil(Duration.ofSeconds(5), () -> {
                List<CheckResultEntity> results = checkResultRepository.findAll();
                assertThat(results).hasSize(1);
                MonitorEntity probed = monitorService.get(monitor.getId());
                assertThat(probed.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
                assertThat(probed.getNextCheckAt())
                        .isEqualTo(results.getFirst().getFinishedAt().plusSeconds(120));
            });
        } finally {
            staleProbeThread.shutdownNow();
        }
    }

    @Test
    void discardsInFlightResultWhenMethodChanges() throws Exception {
        MonitorEntity monitor = monitorService.create(input("method-change", "/a", "GET", 60,
                3000, 10000, 200, 299, true));
        CLOCK.advance(Duration.ofSeconds(30));

        ExecutorService staleProbeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CheckResultEntity>> staleProbe =
                    staleProbeThread.submit(() -> probeService.probe(monitor.getId()));
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            MonitorEntity updated = monitorService.update(monitor.getId(),
                    input("method-change", "/a", "HEAD", 60, 3000, 10000, 200, 299, true));
            assertThat(updated.getConfigVersion()).isEqualTo(2);
            assertThat(updated.getNextCheckAt()).isEqualTo(BASE_TIME.plusSeconds(30));

            aRelease.countDown();

            assertThat(staleProbe.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(checkResultRepository.count()).isZero();
            MonitorEntity afterStale = monitorService.get(monitor.getId());
            assertThat(afterStale.getCurrentStatus()).isEqualTo(MonitorStatus.UNKNOWN);
            assertThat(afterStale.getLastCheckedAt()).isNull();
            assertThat(afterStale.getNextCheckAt()).isEqualTo(BASE_TIME.plusSeconds(30));
        } finally {
            staleProbeThread.shutdownNow();
        }
    }

    @Test
    void discardsInFlightResultWhenMonitorIsDeleted() throws Exception {
        MonitorEntity monitor = monitorService.create(input("deleted", "/a", "GET", 60,
                3000, 10000, 200, 299, true));

        ExecutorService staleProbeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CheckResultEntity>> staleProbe =
                    staleProbeThread.submit(() -> probeService.probe(monitor.getId()));
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            monitorService.delete(monitor.getId());
            aRelease.countDown();

            assertThat(staleProbe.get(10, TimeUnit.SECONDS)).isEmpty();
            assertThat(checkResultRepository.count()).isZero();
            assertThat(monitorRepository.existsById(monitor.getId())).isFalse();
        } finally {
            staleProbeThread.shutdownNow();
        }
    }

    @Test
    void keepsResultWhenDisabledMidFlightAndProbesImmediatelyAfterReenable() throws Exception {
        MonitorEntity monitor = monitorService.create(input("disable", "/a", "GET", 60,
                3000, 10000, 200, 299, true));

        ExecutorService inFlightProbeThread = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CheckResultEntity>> inFlightProbe =
                    inFlightProbeThread.submit(() -> probeService.probe(monitor.getId()));
            assertThat(aStarted.await(5, TimeUnit.SECONDS)).isTrue();

            MonitorEntity disabled = monitorService.update(monitor.getId(),
                    input("disable", "/a", "GET", 60, 3000, 10000, 200, 299, false));
            assertThat(disabled.getConfigVersion()).isEqualTo(1);

            aRelease.countDown();

            Optional<CheckResultEntity> result = inFlightProbe.get(10, TimeUnit.SECONDS);
            assertThat(result).isPresent();
            assertThat(result.orElseThrow().isSuccess()).isTrue();
            MonitorEntity afterProbe = monitorService.get(monitor.getId());
            assertThat(afterProbe.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
            assertThat(afterProbe.getLastCheckedAt()).isEqualTo(result.orElseThrow().getFinishedAt());

            CLOCK.advance(Duration.ofSeconds(120));
            probeScheduler.tick();
            assertThat(checkResultRepository.count()).isEqualTo(1);

            MonitorEntity reenabled = monitorService.update(monitor.getId(),
                    input("disable", "/a", "GET", 60, 3000, 10000, 200, 299, true));
            assertThat(reenabled.getConfigVersion()).isEqualTo(1);
            assertThat(reenabled.getNextCheckAt()).isEqualTo(CLOCK.instant());

            probeScheduler.tick();

            awaitUntil(Duration.ofSeconds(5), () ->
                    assertThat(checkResultRepository.count()).isEqualTo(2));
        } finally {
            inFlightProbeThread.shutdownNow();
        }
    }

    private MonitorEntity update(Long id, MonitorConfigInput input) {
        return monitorService.update(id, input);
    }

    private MonitorConfigInput input(String name, String path, String method, int intervalSeconds,
                                     int connectTimeoutMs, int responseTimeoutMs,
                                     int expectedStatusMin, int expectedStatusMax, boolean enabled) {
        return new MonitorConfigInput(name, "http://127.0.0.1:" + port + path, method,
                intervalSeconds, connectTimeoutMs, responseTimeoutMs,
                expectedStatusMin, expectedStatusMax, enabled, 1, null);
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may close the connection early; tests assert probe outcomes.
        }
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
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
