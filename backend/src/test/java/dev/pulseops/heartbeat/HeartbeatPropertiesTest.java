package dev.pulseops.heartbeat;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatPropertiesTest {

    @Test
    void acceptsDefaults() {
        HeartbeatProperties properties = new HeartbeatProperties(
                false, Duration.ofSeconds(5), 100, true);

        assertThat(properties.requireHttps()).isFalse();
        assertThat(properties.tickInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.schedulingEnabled()).isTrue();
    }

    @Test
    void rejectsTickIntervalBelowMinimum() {
        assertThatThrownBy(() -> new HeartbeatProperties(
                false, Duration.ofMillis(1), 100, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tick-interval");
    }

    @Test
    void rejectsBatchSizeOutsideRange() {
        assertThatThrownBy(() -> new HeartbeatProperties(
                false, Duration.ofSeconds(5), 0, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new HeartbeatProperties(
                false, Duration.ofSeconds(5), 501, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
    }
}
