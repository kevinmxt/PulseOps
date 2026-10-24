package dev.pulseops.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.PulseOpsApplication;
import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.notification.NotificationChannel;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import dev.pulseops.notification.NotificationDeliveryStatus;
import dev.pulseops.notification.NotificationDispatcher;
import dev.pulseops.notification.NotificationEventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Phase2RestartIT {

    private static final Instant STARTED_AT = Instant.parse("2026-10-21T08:00:00Z");
    private static final AtomicInteger TELEGRAM_REQUESTS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static int port;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            TELEGRAM_REQUESTS.incrementAndGet();
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resendsOnlyPendingDeliveriesAfterRestart() {
        Long sentDeliveryId;
        Long pendingDeliveryId;
        Long failedDeliveryId;
        Long retryDeliveryId;

        try (ConfigurableApplicationContext first = startContext()) {
            MonitorRepository monitors = first.getBean(MonitorRepository.class);
            IncidentRepository incidents = first.getBean(IncidentRepository.class);
            NotificationDeliveryRepository deliveries =
                    first.getBean(NotificationDeliveryRepository.class);

            MonitorEntity monitor = monitors.save(monitor());
            IncidentEntity incident = incidents.save(incident(monitor.getId()));

            NotificationDeliveryEntity sent = delivery(incident.getId(),
                    NotificationEventType.INCIDENT_OPENED, NotificationDeliveryStatus.SENT);
            sent.setAttemptCount(1);
            sent.setLastAttemptAt(STARTED_AT);
            sent.setSentAt(STARTED_AT);
            sentDeliveryId = deliveries.save(sent).getId();

            pendingDeliveryId = deliveries.save(delivery(incident.getId(),
                    NotificationEventType.INCIDENT_RESOLVED,
                    NotificationDeliveryStatus.PENDING)).getId();

            IncidentEntity failedIncident = incidents.save(incident(monitor.getId()));
            NotificationDeliveryEntity failed = delivery(failedIncident.getId(),
                    NotificationEventType.INCIDENT_OPENED, NotificationDeliveryStatus.FAILED);
            failed.setAttemptCount(3);
            failed.setLastErrorSummary("Telegram returned HTTP 400");
            failedDeliveryId = deliveries.save(failed).getId();

            IncidentEntity retryIncident = incidents.save(incident(monitor.getId()));
            NotificationDeliveryEntity retrying = delivery(retryIncident.getId(),
                    NotificationEventType.INCIDENT_OPENED, NotificationDeliveryStatus.PENDING);
            retrying.setAttemptCount(2);
            retryDeliveryId = deliveries.save(retrying).getId();
        }

        try (ConfigurableApplicationContext second = startContext()) {
            NotificationDeliveryRepository deliveries =
                    second.getBean(NotificationDeliveryRepository.class);
            second.getBean(NotificationDispatcher.class).dispatchDue();

            NotificationDeliveryEntity sent = deliveries.findById(sentDeliveryId).orElseThrow();
            assertThat(sent.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
            assertThat(sent.getAttemptCount()).isEqualTo(1);
            assertThat(sent.getSentAt()).isEqualTo(STARTED_AT);

            NotificationDeliveryEntity pending = deliveries.findById(pendingDeliveryId).orElseThrow();
            assertThat(pending.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
            assertThat(pending.getAttemptCount()).isEqualTo(1);
            assertThat(pending.getSentAt()).isNotNull();

            NotificationDeliveryEntity failed = deliveries.findById(failedDeliveryId).orElseThrow();
            assertThat(failed.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED);
            assertThat(failed.getAttemptCount()).isEqualTo(3);

            NotificationDeliveryEntity retried = deliveries.findById(retryDeliveryId).orElseThrow();
            assertThat(retried.getStatus()).isEqualTo(NotificationDeliveryStatus.SENT);
            assertThat(retried.getAttemptCount()).isEqualTo(3);
        }

        assertThat(TELEGRAM_REQUESTS).hasValue(2);
    }

    private static ConfigurableApplicationContext startContext() {
        return new SpringApplicationBuilder(PulseOpsApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--pulseops.probe.scheduling-enabled=false",
                        "--pulseops.notification.scheduling-enabled=false",
                        "--pulseops.telegram.bot-token=test-token",
                        "--pulseops.telegram.chat-id=test-chat",
                        "--pulseops.telegram.api-base-url=http://127.0.0.1:" + port,
                        "--spring.main.banner-mode=off");
    }

    private static MonitorEntity monitor() {
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("restart notification");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl("https://example.com/health");
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(1000);
        monitor.setResponseTimeoutMs(2000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UP);
        monitor.setNextCheckAt(STARTED_AT);
        monitor.setCreatedAt(STARTED_AT);
        monitor.setUpdatedAt(STARTED_AT);
        return monitor;
    }

    private static IncidentEntity incident(Long monitorId) {
        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(monitorId);
        incident.setStartedAt(STARTED_AT);
        incident.setResolvedAt(STARTED_AT.plusSeconds(60));
        incident.setStatus(IncidentStatus.RESOLVED);
        incident.setFailureType(CheckFailureType.CONNECTION_FAILED);
        incident.setSummary("连续 3 次探测失败，最新失败：连接失败");
        return incident;
    }

    private static NotificationDeliveryEntity delivery(Long incidentId,
                                                      NotificationEventType eventType,
                                                      NotificationDeliveryStatus status) {
        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
        delivery.setIncidentId(incidentId);
        delivery.setChannel(NotificationChannel.TELEGRAM);
        delivery.setEventType(eventType);
        delivery.setStatus(status);
        delivery.setNextAttemptAt(Instant.EPOCH);
        return delivery;
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may close the connection early; tests assert the delivery outcome.
        }
    }
}
