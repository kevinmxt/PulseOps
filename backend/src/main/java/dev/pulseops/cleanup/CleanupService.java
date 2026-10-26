package dev.pulseops.cleanup;

import dev.pulseops.cleanup.RetentionCleaner.IncidentDeletionResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

@Service
public class CleanupService {

    static final Duration BATCH_PAUSE = Duration.ofMillis(50);
    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);
    private static final String REMOVED_COUNTER = "cleanup.removed";

    private final CleanupProperties properties;
    private final RetentionCleaner retentionCleaner;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public CleanupService(CleanupProperties properties,
                          RetentionCleaner retentionCleaner,
                          PlatformTransactionManager transactionManager,
                          MeterRegistry meterRegistry,
                          Clock clock) {
        this.properties = properties;
        this.retentionCleaner = retentionCleaner;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public CleanupTickResult tick() {
        if (!properties.enabled()) {
            return CleanupTickResult.NONE;
        }

        Instant now = clock.instant();
        BatchBudget budget = new BatchBudget(properties.maxBatchesPerTick());

        Instant checkResultCutoff = now.minus(Duration.ofDays(properties.checkResultRetentionDays()));
        BatchOutcome results = runBatches(budget, () -> new BatchOutcome(
                retentionCleaner.deleteExpiredCheckResults(checkResultCutoff, properties.batchSize()),
                0));

        Instant incidentCutoff =
                now.minus(Duration.ofDays(properties.resolvedIncidentRetentionDays()));
        BatchOutcome incidents = runBatches(budget, () -> {
            IncidentDeletionResult deleted = retentionCleaner.deleteExpiredResolvedIncidents(
                    incidentCutoff, properties.batchSize());
            return new BatchOutcome(deleted.incidentsDeleted(), deleted.pendingDeliveriesDeleted());
        });

        recordRemoved("check_result", results.deleted());
        recordRemoved("resolved_incident", incidents.deleted());
        recordRemoved("notification_delivery", incidents.pendingDeliveries());

        CleanupTickResult result = new CleanupTickResult(
                results.deleted(), incidents.deleted(), incidents.pendingDeliveries());
        if (result.isEmpty()) {
            log.debug("Retention cleanup found nothing to delete");
        } else {
            log.info("Retention cleanup deleted data, checkResults={}, resolvedIncidents={}, "
                            + "pendingDeliveries={}",
                    result.checkResultsDeleted(), result.resolvedIncidentsDeleted(),
                    result.pendingDeliveriesDeleted());
        }
        return result;
    }

    private BatchOutcome runBatches(BatchBudget budget, Supplier<BatchOutcome> batch) {
        long deleted = 0;
        long pendingDeliveries = 0;
        while (budget.hasRemaining()) {
            BatchOutcome outcome = Objects.requireNonNull(
                    transactionTemplate.execute(status -> batch.get()));
            deleted += outcome.deleted();
            pendingDeliveries += outcome.pendingDeliveries();
            if (outcome.deleted() == 0) {
                break;
            }
            budget.consume();
            if (outcome.deleted() < properties.batchSize()
                    || !budget.hasRemaining()
                    || !pauseBetweenBatches()) {
                break;
            }
        }
        return new BatchOutcome(deleted, pendingDeliveries);
    }

    private void recordRemoved(String type, long count) {
        if (count > 0) {
            meterRegistry.counter(REMOVED_COUNTER, "type", type).increment(count);
        }
    }

    private static boolean pauseBetweenBatches() {
        try {
            Thread.sleep(BATCH_PAUSE.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private record BatchOutcome(long deleted, long pendingDeliveries) {
    }

    private static final class BatchBudget {

        private int remaining;

        private BatchBudget(int remaining) {
            this.remaining = remaining;
        }

        private boolean hasRemaining() {
            return remaining > 0;
        }

        private void consume() {
            remaining--;
        }
    }

    public record CleanupTickResult(long checkResultsDeleted,
                                    long resolvedIncidentsDeleted,
                                    long pendingDeliveriesDeleted) {

        static final CleanupTickResult NONE = new CleanupTickResult(0, 0, 0);

        boolean isEmpty() {
            return checkResultsDeleted == 0
                    && resolvedIncidentsDeleted == 0
                    && pendingDeliveriesDeleted == 0;
        }
    }
}
