package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeoutHostResolverTest {

    @Test
    void returnsAddressesWhenResolutionCompletesInTime() throws Exception {
        TimeoutHostResolver resolver = new TimeoutHostResolver(
                host -> List.of(InetAddress.getByName("8.8.8.8")),
                Duration.ofSeconds(1));

        assertThat(resolver.resolve("example.com")).hasSize(1);
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
                blockingResolver, Duration.ofMillis(25), 4);

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
        }, Duration.ofSeconds(1), 1);

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
                Duration.ofMillis(50));

        assertThatThrownBy(() -> resolver.resolve("slow.example"))
                .isInstanceOf(UnknownHostException.class);
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
