package dev.pulseops.monitor.target;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutHostResolverTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void returnsAddressesWhenResolutionCompletesInTime() throws Exception {
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                host -> List.of(InetAddress.getByName("8.8.8.8")),
                Duration.ofSeconds(1), clock, meterRegistry);

        assertThat(resolver.resolve("example.com")).hasSize(1);
    }

    @Test
    void reportsAvailablePermits() {
        CountDownLatch release = new CountDownLatch(1);
        HostResolver blockingResolver = host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getByName("8.8.8.8"));
        };
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                blockingResolver, Duration.ofMillis(25), 4, clock, meterRegistry);

        assertThat(availablePermits()).isEqualTo(4.0);

        assertThatThrownBy(() -> resolver.resolve("slow.example"))
                .isInstanceOf(UnknownHostException.class);
        assertThat(availablePermits()).isEqualTo(3.0);

        release.countDown();
        awaitUntil(Duration.ofSeconds(2), () -> assertThat(availablePermits()).isEqualTo(4.0));
    }

    @Test
    void reportsTimeoutWhenPermitRemainsHeld() {
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                host -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                },
                Duration.ofMillis(25), clock, meterRegistry);
        Logger logger = (Logger) LoggerFactory.getLogger(TimeoutHostResolver.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThatThrownBy(() -> resolver.resolve("slow.example"))
                    .isInstanceOf(UnknownHostException.class);

            assertThat(meterRegistry.get("resolver.timeout").counter().count()).isEqualTo(1.0);
            assertThat(appender.list.stream()
                    .filter(event -> event.getLevel() == Level.DEBUG)
                    .filter(event -> event.getFormattedMessage().contains("permit remains held"))
                    .count()).isEqualTo(1);
        } finally {
            detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }

    @Test
    void reportsExhaustionOnceAndRejectsWithBusyMessage() {
        CountDownLatch release = new CountDownLatch(1);
        HostResolver blockingResolver = host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getByName("8.8.8.8"));
        };
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                blockingResolver, Duration.ofMillis(25), 1, clock, meterRegistry);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThatThrownBy(() -> resolver.resolve("slow.example"))
                    .isInstanceOf(UnknownHostException.class);

            for (int attempt = 0; attempt < 3; attempt++) {
                assertThatThrownBy(() -> resolver.resolve("blocked\nexample"))
                        .isInstanceOf(ResolverBusyException.class)
                        .hasMessageContaining("resolver busy");
            }

            assertThat(meterRegistry.get("resolver.exhausted").counter().count()).isEqualTo(3);
            assertThat(resolver.isExhausted()).isTrue();
            assertThat(resolver.exhaustedSince()).isEqualTo(clock.instant());
            List<ILoggingEvent> warnings = warningsContaining(appender, "permits exhausted");
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage()).doesNotContain("\n");
        } finally {
            detachAppender(appender);
            release.countDown();
        }
    }

    @Test
    void logsRecoveryOnceWhenPermitsReturn() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HostResolver blockingResolver = host -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(InetAddress.getByName("8.8.8.8"));
        };
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                blockingResolver, Duration.ofMillis(25), 1, clock, meterRegistry);
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            assertThatThrownBy(() -> resolver.resolve("slow.example"))
                    .isInstanceOf(UnknownHostException.class);
            assertThatThrownBy(() -> resolver.resolve("blocked.example"))
                    .isInstanceOf(UnknownHostException.class);
            assertThat(infosContaining(appender, "available again")).isEmpty();

            release.countDown();
            awaitUntil(Duration.ofSeconds(2), () ->
                    assertThat(infosContaining(appender, "available again")).hasSize(1));

            assertThat(resolver.isExhausted()).isFalse();
            assertThat(resolver.exhaustedSince()).isNull();

            assertThat(resolver.resolve("ok.example")).hasSize(1);
            assertThat(infosContaining(appender, "available again")).hasSize(1);
        } finally {
            detachAppender(appender);
            release.countDown();
        }
    }

    @Test
    void boundsInFlightResolutionsUnderSustainedTimeoutsAndRecovers() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger started = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        HostResolver blockingResolver = host -> {
            started.incrementAndGet();
            active.incrementAndGet();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
            }
            return List.of(InetAddress.getByName("8.8.8.8"));
        };
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                blockingResolver, Duration.ofMillis(25), 4, clock, meterRegistry);

        long startNanos = System.nanoTime();
        for (int attempt = 0; attempt < 40; attempt++) {
            assertThatThrownBy(() -> resolver.resolve("slow.example"))
                    .isInstanceOf(UnknownHostException.class);
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);

        awaitUntil(Duration.ofSeconds(2), () -> {
            assertThat(started.get()).isEqualTo(4);
            assertThat(active.get()).isEqualTo(4);
        });
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));

        release.countDown();
        awaitUntil(Duration.ofSeconds(2), () -> assertThat(active.get()).isZero());
        awaitUntil(Duration.ofSeconds(2), () ->
                assertThat(resolver.resolve("recovered.example")).hasSize(1));
    }

    @Test
    void releasesPermitWhenResolutionFails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TimeoutHostResolver resolver = new TimeoutHostResolver(host -> {
            if (calls.incrementAndGet() == 1) {
                throw new UnknownHostException(host);
            }
            return List.of(InetAddress.getByName("8.8.8.8"));
        }, Duration.ofSeconds(1), 1, clock, meterRegistry);

        assertThatThrownBy(() -> resolver.resolve("fail.example"))
                .isInstanceOf(UnknownHostException.class);
        assertThat(resolver.resolve("ok.example")).hasSize(1);
    }

    @Test
    void failsWhenResolutionExceedsTimeout() {
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                host -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                },
                Duration.ofMillis(50), clock, meterRegistry);

        assertThatThrownBy(() -> resolver.resolve("slow.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    private double availablePermits() {
        return meterRegistry.get("resolver.permits.available").gauge().value();
    }

    private static ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(TimeoutHostResolver.class)).addAppender(appender);
        return appender;
    }

    private static void detachAppender(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(TimeoutHostResolver.class)).detachAppender(appender);
    }

    private static List<ILoggingEvent> warningsContaining(ListAppender<ILoggingEvent> appender, String fragment) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .toList();
    }

    private static List<ILoggingEvent> infosContaining(ListAppender<ILoggingEvent> appender, String fragment) {
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .toList();
    }

    private static void awaitUntil(Duration timeout, ThrowingRunnable assertion) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (Throwable failure) {
                if (System.nanoTime() >= deadline) {
                    if (failure instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw new AssertionError(failure);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
