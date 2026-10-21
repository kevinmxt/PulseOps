package dev.pulseops.monitor.probe;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProbeSchedulingPropertiesTest {

    @Test
    void acceptsDefaults() {
        ProbeSchedulingProperties properties = new ProbeSchedulingProperties(
                Duration.ofSeconds(1), 200, 8, 100);

        assertThat(properties.tickInterval()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void rejectsTickIntervalBelowMinimum() {
        assertThatThrownBy(() -> new ProbeSchedulingProperties(
                Duration.ofMillis(1), 200, 8, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tick-interval");
    }

    @Test
    void rejectsNonPositivePoolSize() {
        assertThatThrownBy(() -> new ProbeSchedulingProperties(
                Duration.ofSeconds(1), 200, 0, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool-size");
    }

    @Test
    void rejectsNegativeQueueCapacity() {
        assertThatThrownBy(() -> new ProbeSchedulingProperties(
                Duration.ofSeconds(1), 200, 8, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queue-capacity");
    }
}
