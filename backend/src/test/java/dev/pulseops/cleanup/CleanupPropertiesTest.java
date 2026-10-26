package dev.pulseops.cleanup;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CleanupPropertiesTest {

    @Test
    void acceptsBoundaryValues() {
        assertThatCode(() -> new CleanupProperties(true, true, Duration.ofMinutes(1),
                1, 1, 1, 1)).doesNotThrowAnyException();
        assertThatCode(() -> new CleanupProperties(true, true, Duration.ofHours(24),
                3650, 3650, 10000, 1000)).doesNotThrowAnyException();
    }

    @Test
    void defaultsNullTickIntervalToOneHour() {
        CleanupProperties properties = new CleanupProperties(true, true, null,
                30, 90, 1000, 50);

        assertThat(properties.tickInterval()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void rejectsTickIntervalOutsideRange() {
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofSeconds(59),
                30, 90, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tick-interval");
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(25),
                30, 90, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tick-interval");
    }

    @Test
    void rejectsCheckResultRetentionOutsideRange() {
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                0, 90, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("check-result-retention-days");
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                3651, 90, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("check-result-retention-days");
    }

    @Test
    void rejectsResolvedIncidentRetentionOutsideRange() {
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 0, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved-incident-retention-days");
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 3651, 1000, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolved-incident-retention-days");
    }

    @Test
    void rejectsBatchSizeOutsideRange() {
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 90, 0, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 90, 10001, 50))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("batch-size");
    }

    @Test
    void rejectsMaxBatchesOutsideRange() {
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 90, 1000, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches-per-tick");
        assertThatThrownBy(() -> new CleanupProperties(true, true, Duration.ofHours(1),
                30, 90, 1000, 1001))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-batches-per-tick");
    }
}
