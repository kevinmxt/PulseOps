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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.probe.batch-size=2"
})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(ProbeSchedulerIT.SchedulerTestConfiguration.class)
class ProbeSchedulerIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);
    private static final AtomicInteger okHits = new AtomicInteger();
    private static final AtomicInteger blockHits = new AtomicInteger();

    private static volatile CountDownLatch blockStarted = new CountDownLatch(1);
    private static volatile CountDownLatch blockRelease = new CountDownLatch(1);

    private static HttpServer server;
    private static ExecutorService serverExecutor;
    private static int port;

    @Autowired
    ProbeScheduler probeScheduler;

    @Autowired
    MonitorService monitorService;

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @Autowired
    @Qualifier("probeExecutor")
    ThreadPoolTaskExecutor probeExecutor;

    @TestConfiguration(proxyBeanMethods = false)
    static class SchedulerTestConfiguration {

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
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            respond(exchange, 200, "ok");
        });
        server.createContext("/block", exchange -> {
            blockHits.incrementAndGet();
            blockStarted.countDown();
            awaitQuietly(blockRelease);
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
    void resetState() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        CLOCK.reset();
        okHits.set(0);
        blockHits.set(0);
        blockStarted = new CountDownLatch(1);
        blockRelease = new CountDownLatch(1);
    }

    @AfterEach
    void releaseBlockedRequests() {
        blockRelease.countDown();
    }

    @Test
    void probesDueMonitorAndAdvancesNextCheckAt() {
        MonitorEntity monitor = createMonitor("scheduled", true, 60, "/ok");

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(5), () -> {
            CheckResultEntity result = onlyResult();
            assertThat(result.isSuccess()).isTrue();
            MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
            assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
            assertThat(updated.getLastCheckedAt()).isEqualTo(result.getFinishedAt());
            assertThat(updated.getNextCheckAt())
                    .isEqualTo(result.getFinishedAt().plusSeconds(updated.getIntervalSeconds()));
        });
    }

    @Test
    void waitsForNextCheckAtAndAppliesUpdatedInterval() {
        MonitorEntity monitor = createMonitor("interval", true, 60, "/ok");

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () -> onlyResult());
        CheckResultEntity first = onlyResult();
        assertThat(first.getFinishedAt()).isEqualTo(BASE_TIME);

        probeScheduler.tick();
        assertThat(checkResultRepository.count()).isEqualTo(1);

        CLOCK.advance(Duration.ofSeconds(60));
        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(2));
        CheckResultEntity second = latestResult();
        assertThat(second.getFinishedAt()).isEqualTo(BASE_TIME.plusSeconds(60));

        monitorService.update(monitor.getId(), config("interval", true, 120, "/ok"));
        MonitorEntity afterUpdate = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(afterUpdate.getIntervalSeconds()).isEqualTo(120);
        assertThat(afterUpdate.getNextCheckAt()).isEqualTo(CLOCK.instant());

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(3));
        CheckResultEntity third = latestResult();
        MonitorEntity afterProbe = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(afterProbe.getNextCheckAt())
                .isEqualTo(third.getFinishedAt().plusSeconds(120));
    }

    @Test
    void skipsDisabledMonitorAndResumesImmediatelyWhenReenabled() {
        MonitorEntity monitor = createMonitor("toggle", true, 60, "/ok");

        monitorService.update(monitor.getId(), config("toggle", false, 60, "/ok"));
        for (int tick = 0; tick < 3; tick++) {
            probeScheduler.tick();
            CLOCK.advance(Duration.ofSeconds(60));
        }
        sleep(300);
        assertThat(checkResultRepository.count()).isZero();
        assertThat(okHits).hasValue(0);

        monitorService.update(monitor.getId(), config("toggle", true, 60, "/ok"));
        MonitorEntity reenabled = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(reenabled.getNextCheckAt()).isEqualTo(CLOCK.instant());

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () -> assertThat(onlyResult().isSuccess()).isTrue());
        assertThat(okHits).hasValue(1);
    }

    @Test
    void doesNotProbeSameMonitorConcurrently() {
        MonitorEntity monitor = createMonitor("single-flight", true, 60, "/block");

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () -> assertThat(blockStarted.getCount()).isZero());

        probeScheduler.tick();
        sleep(300);
        assertThat(blockHits).hasValue(1);

        blockRelease.countDown();
        awaitUntil(Duration.ofSeconds(5), () -> {
            CheckResultEntity result = onlyResult();
            assertThat(result.getMonitorId()).isEqualTo(monitor.getId());
        });
        assertThat(blockHits).hasValue(1);
    }

    @Test
    void slowTargetDoesNotBlockTickOrOtherMonitors() {
        MonitorEntity slow = createMonitor("slow", true, 60, "/block");
        MonitorEntity fast = createMonitor("fast", true, 60, "/ok");

        long startedNanos = System.nanoTime();
        probeScheduler.tick();
        assertThat(Duration.ofNanos(System.nanoTime() - startedNanos))
                .isLessThan(Duration.ofSeconds(1));

        awaitUntil(Duration.ofSeconds(5), () -> {
            List<CheckResultEntity> results = checkResultRepository.findAll();
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().getMonitorId()).isEqualTo(fast.getId());
            assertThat(results.getFirst().isSuccess()).isTrue();
        });

        blockRelease.countDown();
        awaitUntil(Duration.ofSeconds(5), () -> {
            List<CheckResultEntity> results = checkResultRepository.findAll();
            assertThat(results).hasSize(2);
            assertThat(results).anySatisfy(result -> {
                assertThat(result.getMonitorId()).isEqualTo(slow.getId());
                assertThat(result.isSuccess()).isTrue();
            });
        });
    }

    @Test
    void allowsInFlightProbeToFinishAfterDisableButStopsFutureProbes() {
        MonitorEntity monitor = createMonitor("disable-in-flight", true, 60, "/block");

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () -> assertThat(blockStarted.getCount()).isZero());

        monitorService.update(monitor.getId(), config("disable-in-flight", false, 60, "/block"));

        blockRelease.countDown();
        awaitUntil(Duration.ofSeconds(5), () -> onlyResult());
        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
        assertThat(updated.getLastCheckedAt()).isEqualTo(onlyResult().getFinishedAt());

        CLOCK.advance(Duration.ofSeconds(120));
        probeScheduler.tick();
        sleep(300);
        assertThat(checkResultRepository.count()).isEqualTo(1);
    }

    @Test
    void discardsInFlightResultWhenMonitorIsDeleted() {
        MonitorEntity monitor = createMonitor("deleted", true, 60, "/block");

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () -> assertThat(blockStarted.getCount()).isZero());

        monitorRepository.deleteById(monitor.getId());
        blockRelease.countDown();

        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(probeScheduler.isInFlight(monitor.getId())).isFalse());
        assertThat(checkResultRepository.count()).isZero();
        assertThat(monitorRepository.existsById(monitor.getId())).isFalse();
    }

    @Test
    void probesMonitorWhoseNextCheckAtIsAlreadyOverdueAtStartup() {
        MonitorEntity monitor = createMonitor("restart", false, 60, "/ok");
        monitor.setEnabled(true);
        monitor.setNextCheckAt(BASE_TIME.minus(Duration.ofHours(1)));
        monitorRepository.save(monitor);

        probeScheduler.tick();

        awaitUntil(Duration.ofSeconds(5), () -> assertThat(onlyResult().isSuccess()).isTrue());
    }

    @Test
    void processesAtMostBatchSizeMonitorsPerTick() {
        createMonitor("batch-1", true, 60, "/ok");
        createMonitor("batch-2", true, 60, "/ok");
        createMonitor("batch-3", true, 60, "/ok");

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(2));
        sleep(300);
        assertThat(checkResultRepository.count()).isEqualTo(2);

        probeScheduler.tick();
        awaitUntil(Duration.ofSeconds(5), () ->
                assertThat(checkResultRepository.count()).isEqualTo(3));
    }

    @Test
    void configuresBoundedProbeExecutor() {
        assertThat(probeExecutor.getCorePoolSize()).isEqualTo(8);
        assertThat(probeExecutor.getMaxPoolSize()).isEqualTo(8);
        assertThat(probeExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
    }

    @Test
    void skipsRejectedProbesWithoutBreakingTickAndRetriesNextTick(CapturedOutput output) {
        ThreadPoolTaskExecutor executor = limitedExecutor();
        try {
            ProbeScheduler limitedScheduler = new ProbeScheduler(
                    monitorRepository, probeService, CLOCK,
                    new ProbeSchedulingProperties(Duration.ofSeconds(1), 10, 1, 0), executor);
            createMonitor("first", true, 60, "/block");
            createMonitor("second", true, 60, "/block");

            limitedScheduler.tick();
            awaitUntil(Duration.ofSeconds(5), () -> assertThat(blockStarted.getCount()).isZero());
            assertThat(blockHits).hasValue(1);
            assertThat(output).contains("rejected");

            blockRelease.countDown();
            awaitUntil(Duration.ofSeconds(5), () ->
                    assertThat(checkResultRepository.count()).isEqualTo(1));

            limitedScheduler.tick();
            awaitUntil(Duration.ofSeconds(5), () ->
                    assertThat(checkResultRepository.count()).isEqualTo(2));
            assertThat(blockHits).hasValue(2);
        } finally {
            executor.shutdown();
        }
    }

    private static ThreadPoolTaskExecutor limitedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("probe-test-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    private MonitorEntity createMonitor(String name, boolean enabled, int intervalSeconds, String path) {
        return monitorService.create(config(name, enabled, intervalSeconds, path));
    }

    private MonitorConfigInput config(String name, boolean enabled, int intervalSeconds, String path) {
        return new MonitorConfigInput(
                name,
                "http://127.0.0.1:" + port + path,
                "GET",
                intervalSeconds,
                3000,
                10000,
                200,
                299,
                enabled);
    }

    private CheckResultEntity latestResult() {
        return checkResultRepository.findAll().stream()
                .max(Comparator.comparing(CheckResultEntity::getId))
                .orElseThrow();
    }

    private CheckResultEntity onlyResult() {
        List<CheckResultEntity> results = checkResultRepository.findAll();
        assertThat(results).hasSize(1);
        return results.getFirst();
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
            // The client may close the connection early; tests assert probe outcomes.
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
