package dev.pulseops.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.PulseOpsApplication;
import dev.pulseops.heartbeat.HeartbeatScheduler;
import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentStatus;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import dev.pulseops.notification.NotificationDeliveryStatus;
import dev.pulseops.notification.NotificationDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Phase3RestartIT {

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
    void restartDetectsTimeoutsWithoutDuplicatesAndSendsPendingNotifications() {
        Instant preRestartDeadline = Instant.now().minusSeconds(180);
        Instant dueAfterRestartDeadline = Instant.now().minusSeconds(60);
        Long preRestartMonitorId;
        Long dueAfterRestartMonitorId;

        try (ConfigurableApplicationContext first = startContext()) {
            MonitorRepository monitors = first.getBean(MonitorRepository.class);
            IncidentRepository incidents = first.getBean(IncidentRepository.class);
            CheckResultRepository checkResults = first.getBean(CheckResultRepository.class);
            NotificationDeliveryRepository deliveries = first.getBean(NotificationDeliveryRepository.class);

            MonitorEntity preRestart = monitors.save(heartbeat("pre-restart", preRestartDeadline));
            preRestartMonitorId = preRestart.getId();

            first.getBean(HeartbeatScheduler.class).tick();

            assertThat(incidentsFor(incidents, preRestartMonitorId)).hasSize(1);
            assertThat(timeoutResults(checkResults, preRestartMonitorId)).hasSize(1);
            assertThat(deliveries.findAll()).hasSize(1)
                    .allMatch(delivery -> delivery.getStatus() == NotificationDeliveryStatus.PENDING);
            assertThat(monitors.findById(preRestartMonitorId).orElseThrow().getCurrentStatus())
                    .isEqualTo(MonitorStatus.DOWN);

            MonitorEntity replay = monitors.findById(preRestartMonitorId).orElseThrow();
            replay.setNextCheckAt(Instant.now().minusSeconds(60));
            monitors.save(replay);

            dueAfterRestartMonitorId = monitors.save(
                    heartbeat("due-after-restart", dueAfterRestartDeadline)).getId();
        }

        try (ConfigurableApplicationContext second = startContext()) {
            MonitorRepository monitors = second.getBean(MonitorRepository.class);
            IncidentRepository incidents = second.getBean(IncidentRepository.class);
            CheckResultRepository checkResults = second.getBean(CheckResultRepository.class);
            NotificationDeliveryRepository deliveries = second.getBean(NotificationDeliveryRepository.class);

            HeartbeatScheduler scheduler = second.getBean(HeartbeatScheduler.class);
            scheduler.tick();
            scheduler.tick();

            List<IncidentEntity> preRestartIncidents = incidentsFor(incidents, preRestartMonitorId);
            assertThat(preRestartIncidents).hasSize(1);
            assertThat(preRestartIncidents.getFirst().getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(preRestartIncidents.getFirst().getStartedAt()).isEqualTo(preRestartDeadline);
            assertThat(timeoutResults(checkResults, preRestartMonitorId)).hasSize(1);

            List<IncidentEntity> dueIncidents = incidentsFor(incidents, dueAfterRestartMonitorId);
            assertThat(dueIncidents).hasSize(1);
            IncidentEntity dueIncident = dueIncidents.getFirst();
            assertThat(dueIncident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(dueIncident.getFailureType()).isEqualTo(CheckFailureType.HEARTBEAT_TIMEOUT);
            assertThat(dueIncident.getStartedAt()).isEqualTo(dueAfterRestartDeadline);
            assertThat(timeoutResults(checkResults, dueAfterRestartMonitorId)).hasSize(1);

            assertThat(deliveries.findAll()).hasSize(2)
                    .allMatch(delivery -> delivery.getStatus() == NotificationDeliveryStatus.PENDING);

            second.getBean(NotificationDispatcher.class).dispatchDue();

            assertThat(deliveries.findAll()).allMatch(delivery ->
                    delivery.getStatus() == NotificationDeliveryStatus.SENT
                            && delivery.getAttemptCount() == 1);
            assertThat(monitors.findById(preRestartMonitorId).orElseThrow().getCurrentStatus())
                    .isEqualTo(MonitorStatus.DOWN);
            assertThat(monitors.findById(dueAfterRestartMonitorId).orElseThrow().getCurrentStatus())
                    .isEqualTo(MonitorStatus.DOWN);
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
                        "--spring.datasource.hikari.minimum-idle=0",
                        "--pulseops.probe.scheduling-enabled=false",
                        "--pulseops.heartbeat.scheduling-enabled=false",
                        "--pulseops.notification.scheduling-enabled=false",
                        "--pulseops.telegram.bot-token=test-token",
                        "--pulseops.telegram.chat-id=test-chat",
                        "--pulseops.telegram.api-base-url=http://127.0.0.1:" + port,
                        "--spring.main.banner-mode=off");
    }

    private static MonitorEntity heartbeat(String name, Instant nextCheckAt) {
        Instant createdAt = nextCheckAt.minusSeconds(300);
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName(name);
        monitor.setType(MonitorType.HEARTBEAT);
        monitor.setIntervalSeconds(60);
        monitor.setHeartbeatGraceSeconds(60);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(nextCheckAt);
        monitor.setCreatedAt(createdAt);
        monitor.setUpdatedAt(createdAt);
        return monitor;
    }

    private static List<IncidentEntity> incidentsFor(IncidentRepository incidents, Long monitorId) {
        return incidents.findAll().stream()
                .filter(incident -> incident.getMonitorId().equals(monitorId))
                .toList();
    }

    private static List<CheckResultEntity> timeoutResults(CheckResultRepository checkResults, Long monitorId) {
        return checkResults.findByMonitorId(monitorId, PageRequest.of(0, 10)).getContent().stream()
                .filter(result -> result.getFailureType() == CheckFailureType.HEARTBEAT_TIMEOUT)
                .toList();
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (IOException ignored) {
            // The client may close the connection early; tests assert delivery outcomes.
        }
    }
}
