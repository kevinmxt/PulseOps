package dev.pulseops.notification;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationPropertiesTest {

    @Test
    void acceptsValidValues() {
        NotificationProperties properties = new NotificationProperties(
                Duration.ofSeconds(5), 50, true);

        assertThat(properties.dispatchInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.batchSize()).isEqualTo(50);
        assertThat(properties.schedulingEnabled()).isTrue();
    }

    @Test
    void defaultsMissingDispatchInterval() {
        NotificationProperties properties = new NotificationProperties(null, 50, true);

        assertThat(properties.dispatchInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void rejectsTooShortDispatchInterval() {
        assertThatThrownBy(() -> new NotificationProperties(
                Duration.ofMillis(50), 50, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dispatch-interval");
    }

    @Test
    void rejectsOutOfRangeBatchSize() {
        assertThatThrownBy(() -> new NotificationProperties(Duration.ofSeconds(5), 0, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new NotificationProperties(Duration.ofSeconds(5), 501, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
    }
}
