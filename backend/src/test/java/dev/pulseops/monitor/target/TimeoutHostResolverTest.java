package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

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
}
