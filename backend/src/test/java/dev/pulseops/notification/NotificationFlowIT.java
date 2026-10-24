package dev.pulseops.notification;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.probe.ProbeService;
import dev.pulseops.monitor.target.TargetPolicy;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pulseops.probe.scheduling-enabled=false",
        "pulseops.notification.scheduling-enabled=false",
        "pulseops.telegram.bot-token=test-token",
        "pulseops.telegram.chat-id=test-chat",
        "logging.level.dev.pulseops.notification.NotificationDispatcher=DEBUG"
})
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@Import(NotificationFlowIT.NotificationTestConfiguration.class)
class NotificationFlowIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    private static final HttpServer SERVER = startServer();
    private static final int PORT = SERVER.getAddress().getPort();
    private static final List<RecordedRequest> TELEGRAM_REQUESTS = new CopyOnWriteArrayList<>();

    private static volatile boolean failing;
    private static volatile int telegramStatus = 200;
    private static volatile boolean telegramBlocks;
    private static volatile CountDownLatch telegramRequestStarted = new CountDownLatch(1);
    private static volatile CountDownLatch releaseTelegramRequest = new CountDownLatch(1);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

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
    NotificationDeliveryRepository notificationDeliveryRepository;

    @Autowired
    NotificationDispatcher notificationDispatcher;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    @Qualifier("notificationTickScheduler")
    ThreadPoolTaskScheduler notificationTickScheduler;

    @TestConfiguration(proxyBeanMethods = false)
    static class NotificationTestConfiguration {

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

    @DynamicPropertySource
    static void telegramProperties(DynamicPropertyRegistry registry) {
        registry.add("pulseops.telegram.api-base-url", () -> "http://127.0.0.1:" + PORT);
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/target", exchange ->
                    respond(exchange, failing ? 503 : 200, failing ? "unavailable" : "ok"));
            server.createContext("/", exchange -> {
                if (exchange.getRequestURI().getPath().startsWith("/bot")) {
                    TELEGRAM_REQUESTS.add(new RecordedRequest(
                            exchange.getRequestURI().getPath(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                    if (telegramBlocks) {
                        telegramRequestStarted.countDown();
                        awaitQuietly(releaseTelegramRequest);
                    }
                    respond(exchange, telegramStatus,
                            telegramStatus == 200 ? "{\"ok\":true}" : "failure");
                } else {
                    respond(exchange, 404, "not found");
                }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @BeforeEach
    void resetState() {
        notificationDeliveryRepository.deleteAll();
        incidentRepository.deleteAll();
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
        TELEGRAM_REQUESTS.clear();
        CLOCK.reset();
        failing = false;
        telegramStatus = 200;
        telegramBlocks = false;
        telegramRequestStarted = new CountDownLatch(1);
        releaseTelegramRequest = new CountDownLatch(1);
    }

    @Test
    void createsPendingDeliveryInTheSameProbeAsTheIncident() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);

        assertThat(incidentRepository.count()).isEqualTo(1);
        List<NotificationDeliveryEntity> deliveries = notificationDeliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        NotificationDeliveryEntity delivery = deliveries.getFirst();
        assertThat(delivery.getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
        assertThat(delivery.getChannel()).isEqualTo(NotificationChannel.TELEGRAM);
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isZero();
        assertThat(delivery.getNextAttemptAt()).isEqualTo(CLOCK.instant());
    }

    @Test
    void dispatchesOpenedNotificationAndMarksDeliverySent() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(onlyDelivery().getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        double sentBefore = meterRegistry.counter("notification.sent").count();
        assertThat(meterRegistry.get("notification.pending").gauge().value()).isEqualTo(1);

        notificationDispatcher.dispatchDue();

        assertThat(meterRegistry.counter("notification.sent").count()).isEqualTo(sentBefore + 1);
        assertThat(meterRegistry.get("notification.pending").gauge().value()).isZero();

        NotificationDeliveryEntity delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastAttemptAt()).isEqualTo(CLOCK.instant());
        assertThat(delivery.getSentAt()).isEqualTo(CLOCK.instant());
        assertThat(delivery.getLastErrorSummary()).isNull();

        assertThat(TELEGRAM_REQUESTS).hasSize(1);
        RecordedRequest request = TELEGRAM_REQUESTS.getFirst();
        assertThat(request.path()).isEqualTo("/bottest-token/sendMessage");
        assertThat(request.body())
                .contains("\"chat_id\":\"test-chat\"")
                .contains("notification target")
                .contains("故障告警")
                .contains("非预期状态码")
                .contains("2026-10-21 08:00:00 UTC");
    }

    @Test
    void dispatchesRecoveryNotification() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        notificationDispatcher.dispatchDue();

        failing = false;
        probe(monitor);
        probe(monitor);
        notificationDispatcher.dispatchDue();

        List<NotificationDeliveryEntity> deliveries = notificationDeliveryRepository.findAll();
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).allSatisfy(delivery ->
                assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT));
        assertThat(deliveries)
                .anySatisfy(delivery ->
                        assertThat(delivery.getEventType()).isEqualTo(NotificationEventType.INCIDENT_RESOLVED));
        assertThat(TELEGRAM_REQUESTS).hasSize(2);
        assertThat(TELEGRAM_REQUESTS.get(1).body())
                .contains("故障恢复")
                .contains("恢复时间")
                .contains("notification target");
    }

    @Test
    void disablingMonitorClosesIncidentWithoutNotification() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        assertThat(onlyDelivery().getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);

        updateEnabled(monitor, false);

        List<NotificationDeliveryEntity> deliveries = notificationDeliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().getEventType()).isEqualTo(NotificationEventType.INCIDENT_OPENED);
    }

    @Test
    void processesAtMostTheBatchSizePerDispatch() {
        MonitorEntity monitor = createMonitor();
        seedDueDeliveries(monitor, 60);

        notificationDispatcher.dispatchDue();
        assertThat(notificationDeliveryRepository.countByStatus(NotificationDeliveryStatus.SENT))
                .isEqualTo(50);

        notificationDispatcher.dispatchDue();
        assertThat(notificationDeliveryRepository.countByStatus(NotificationDeliveryStatus.SENT))
                .isEqualTo(60);
        assertThat(TELEGRAM_REQUESTS).hasSize(60);
    }

    @Test
    void failingNotificationDoesNotBlockProbeExecution() throws Exception {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        telegramStatus = 500;
        telegramBlocks = true;

        ExecutorService dispatcherThread = Executors.newSingleThreadExecutor();
        try {
            Future<?> dispatch = dispatcherThread.submit(notificationDispatcher::dispatchDue);
            assertThat(telegramRequestStarted.await(5, TimeUnit.SECONDS)).isTrue();

            probe(monitor);
            assertThat(checkResultRepository.count()).isEqualTo(4);

            releaseTelegramRequest.countDown();
            dispatch.get(10, TimeUnit.SECONDS);
        } finally {
            releaseTelegramRequest.countDown();
            dispatcherThread.shutdownNow();
        }

        assertThat(onlyDelivery().getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
    }

    @Test
    void runsNotificationsOnADedicatedSchedulerPool() {
        assertThat(notificationTickScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isEqualTo(1);
    }

    @Test
    void retriesServerErrorsWithBackoffBeforeFailing(CapturedOutput output) {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        telegramStatus = 500;
        double failedBefore = meterRegistry.counter("notification.failed").count();

        notificationDispatcher.dispatchDue();
        NotificationDeliveryEntity firstAttempt = onlyDelivery();
        assertThat(firstAttempt.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(firstAttempt.getAttemptCount()).isEqualTo(1);
        assertThat(firstAttempt.getNextAttemptAt()).isEqualTo(BASE_TIME.plusSeconds(60));

        CLOCK.advance(Duration.ofSeconds(30));
        notificationDispatcher.dispatchDue();
        assertThat(onlyDelivery().getAttemptCount()).isEqualTo(1);
        assertThat(meterRegistry.counter("notification.failed").count()).isEqualTo(failedBefore);

        CLOCK.advance(Duration.ofSeconds(30));
        notificationDispatcher.dispatchDue();
        NotificationDeliveryEntity secondAttempt = onlyDelivery();
        assertThat(secondAttempt.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(secondAttempt.getAttemptCount()).isEqualTo(2);
        assertThat(secondAttempt.getNextAttemptAt()).isEqualTo(BASE_TIME.plusSeconds(360));
        assertThat(meterRegistry.counter("notification.failed").count()).isEqualTo(failedBefore);

        CLOCK.advance(Duration.ofMinutes(5));
        notificationDispatcher.dispatchDue();
        NotificationDeliveryEntity failed = onlyDelivery();
        assertThat(failed.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(failed.getAttemptCount()).isEqualTo(3);
        assertThat(failed.getLastErrorSummary())
                .contains("HTTP 500")
                .doesNotContain("test-token");
        assertThat(meterRegistry.counter("notification.failed").count()).isEqualTo(failedBefore + 1);

        int requestsAfterFailure = TELEGRAM_REQUESTS.size();
        CLOCK.advance(Duration.ofHours(1));
        notificationDispatcher.dispatchDue();
        assertThat(onlyDelivery().getAttemptCount()).isEqualTo(3);
        assertThat(TELEGRAM_REQUESTS).hasSize(requestsAfterFailure);

        assertThat(output.getOut())
                .containsPattern("DEBUG.*Notification retry scheduled")
                .containsPattern("WARN.*Notification delivery failed")
                .doesNotContain("test-token");
    }

    @Test
    void deliversAfterTransientFailure() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        telegramStatus = 500;
        notificationDispatcher.dispatchDue();
        assertThat(onlyDelivery().getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);

        telegramStatus = 200;
        CLOCK.advance(Duration.ofMinutes(1));
        notificationDispatcher.dispatchDue();

        NotificationDeliveryEntity delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(delivery.getAttemptCount()).isEqualTo(2);
        assertThat(delivery.getSentAt()).isEqualTo(CLOCK.instant());
        assertThat(TELEGRAM_REQUESTS).hasSize(2);
    }

    @Test
    void retriesRateLimiting() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        telegramStatus = 429;

        notificationDispatcher.dispatchDue();

        NotificationDeliveryEntity delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(BASE_TIME.plusSeconds(60));
    }

    @Test
    void clientErrorMarksDeliveryFailedWithoutChangingIncident() {
        MonitorEntity monitor = createMonitor();
        failing = true;
        probe(monitor);
        probe(monitor);
        probe(monitor);
        telegramStatus = 400;
        double failedBefore = meterRegistry.counter("notification.failed").count();

        notificationDispatcher.dispatchDue();

        assertThat(meterRegistry.counter("notification.failed").count()).isEqualTo(failedBefore + 1);
        NotificationDeliveryEntity delivery = onlyDelivery();
        assertThat(delivery.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(delivery.getAttemptCount()).isEqualTo(1);
        assertThat(delivery.getLastErrorSummary())
                .contains("HTTP 400")
                .doesNotContain("test-token");
        assertThat(incidentRepository.findAll().getFirst().getStatus())
                .isEqualTo(dev.pulseops.incident.IncidentStatus.OPEN);

        probe(monitor);
        assertThat(checkResultRepository.count()).isEqualTo(4);
    }

    private void seedDueDeliveries(MonitorEntity monitor, int count) {
        for (int i = 0; i < count; i++) {
            IncidentEntity incident = new IncidentEntity();
            incident.setMonitorId(monitor.getId());
            incident.setStartedAt(BASE_TIME.minusSeconds(count - i));
            incident.setStatus(IncidentStatus.RESOLVED);
            incident.setResolvedAt(incident.getStartedAt().plusSeconds(30));
            incident.setFailureType(CheckFailureType.CONNECTION_FAILED);
            incident.setSummary("连续 3 次探测失败，最新失败：连接失败");
            IncidentEntity saved = incidentRepository.save(incident);

            NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
            delivery.setIncidentId(saved.getId());
            delivery.setChannel(NotificationChannel.TELEGRAM);
            delivery.setEventType(NotificationEventType.INCIDENT_OPENED);
            delivery.setStatus(NotificationDeliveryStatus.PENDING);
            delivery.setNextAttemptAt(BASE_TIME);
            notificationDeliveryRepository.save(delivery);
        }
    }

    private NotificationDeliveryEntity onlyDelivery() {
        List<NotificationDeliveryEntity> deliveries = notificationDeliveryRepository.findAll();
        assertThat(deliveries).hasSize(1);
        return deliveries.getFirst();
    }

    private MonitorEntity createMonitor() {
        return monitorService.create(new MonitorConfigInput(
                "notification target",
                "http://127.0.0.1:" + PORT + "/target",
                "GET",
                60,
                3000,
                10000,
                200,
                299,
                true,
                null,
                null));
    }

    private void probe(MonitorEntity monitor) {
        probeService.probe(monitor.getId());
    }

    private void updateEnabled(MonitorEntity monitor, boolean enabled) {
        MonitorEntity current = monitorRepository.findById(monitor.getId()).orElseThrow();
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
            // The client may close the connection early; tests assert the outcome.
        }
    }

    record RecordedRequest(String path, String body) {
    }

    static final class MutableClock extends Clock {

        private volatile Instant current;

        MutableClock(Instant initial) {
            this.current = initial;
        }

        void reset() {
            current = BASE_TIME;
        }

        void advance(java.time.Duration duration) {
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
