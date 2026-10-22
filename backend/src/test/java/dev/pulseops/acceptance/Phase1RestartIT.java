package dev.pulseops.acceptance;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.pulseops.PulseOpsApplication;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.target.TargetPolicy;
import org.apache.hc.client5.http.DnsResolver;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class Phase1RestartIT {

    private static final Instant BASE_TIME = Instant.parse("2026-10-21T08:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(BASE_TIME);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static HttpServer server;
    private static int port;

    @Configuration(proxyBeanMethods = false)
    static class RestartTestConfiguration {

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
    void keepsConfigurationAndHistoryAndResumesOverdueProbeAfterRestart() {
        Long monitorId;
        Instant previousCheckAt = BASE_TIME.minusSeconds(60);

        try (ConfigurableApplicationContext first = startContext(false)) {
            MonitorRepository monitors = first.getBean(MonitorRepository.class);
            CheckResultRepository results = first.getBean(CheckResultRepository.class);

            MonitorEntity monitor = monitors.save(monitor());
            monitorId = monitor.getId();
            results.save(previousResult(monitorId, previousCheckAt));
            assertThat(results.count()).isEqualTo(1);
        }

        CLOCK.advance(Duration.ofSeconds(120));

        try (ConfigurableApplicationContext second = startContext(true)) {
            MonitorRepository monitors = second.getBean(MonitorRepository.class);
            CheckResultRepository results = second.getBean(CheckResultRepository.class);

            assertThat(second.getBean(Flyway.class).info().applied()).isNotEmpty();
            MonitorEntity reloaded = monitors.findById(monitorId).orElseThrow();
            assertThat(reloaded.getName()).isEqualTo("restart");
            assertThat(reloaded.getTargetUrl()).isEqualTo("http://127.0.0.1:" + port + "/ok");
            assertThat(reloaded.getIntervalSeconds()).isEqualTo(60);
            assertThat(reloaded.isEnabled()).isTrue();
            assertThat(reloaded.getCreatedAt()).isEqualTo(BASE_TIME);

            awaitUntil(Duration.ofSeconds(10), () ->
                    assertThat(results.count()).isEqualTo(2));

            assertThat(results.findAll())
                    .anySatisfy(result -> {
                        assertThat(result.getStartedAt()).isEqualTo(previousCheckAt);
                        assertThat(result.isSuccess()).isTrue();
                    })
                    .anySatisfy(result -> {
                        assertThat(result.getStartedAt()).isEqualTo(BASE_TIME.plusSeconds(120));
                        assertThat(result.isSuccess()).isTrue();
                    });

            MonitorEntity probed = monitors.findById(monitorId).orElseThrow();
            assertThat(probed.getCurrentStatus()).isEqualTo(MonitorStatus.UP);
            assertThat(probed.getNextCheckAt()).isEqualTo(BASE_TIME.plusSeconds(180));
        }
    }

    private static ConfigurableApplicationContext startContext(boolean schedulingEnabled) {
        return new SpringApplicationBuilder(PulseOpsApplication.class, RestartTestConfiguration.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(),
                        "--pulseops.probe.scheduling-enabled=" + schedulingEnabled,
                        "--spring.main.banner-mode=off");
    }

    private static MonitorEntity monitor() {
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("restart");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl("http://127.0.0.1:" + port + "/ok");
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(1000);
        monitor.setResponseTimeoutMs(2000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UP);
        monitor.setLastCheckedAt(BASE_TIME);
        monitor.setLastSuccessAt(BASE_TIME);
        monitor.setNextCheckAt(BASE_TIME);
        monitor.setCreatedAt(BASE_TIME);
        monitor.setUpdatedAt(BASE_TIME);
        return monitor;
    }

    private static CheckResultEntity previousResult(Long monitorId, Instant startedAt) {
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(startedAt);
        result.setFinishedAt(startedAt.plusSeconds(1));
        result.setDurationMs(1000);
        result.setSuccess(true);
        result.setHttpStatusCode(200);
        return result;
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
            // The probe may close the connection early; tests assert the probe outcome.
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
