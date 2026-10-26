package dev.pulseops.cleanup;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class RetentionCleaner {

    private final EntityManager entityManager;

    public RetentionCleaner(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public int deleteExpiredCheckResults(Instant cutoff, int batchSize) {
        return entityManager.createNativeQuery("""
                        DELETE FROM check_result
                        WHERE id IN (
                            SELECT id FROM check_result
                            WHERE started_at < :cutoff
                            ORDER BY started_at, id
                            LIMIT :batchSize
                        )
                        """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", batchSize)
                .executeUpdate();
    }

    @SuppressWarnings("unchecked")
    public IncidentDeletionResult deleteExpiredResolvedIncidents(Instant cutoff, int batchSize) {
        List<Long> ids = entityManager.createNativeQuery("""
                        SELECT id FROM incident
                        WHERE status = 'RESOLVED' AND resolved_at < :cutoff
                        ORDER BY resolved_at, id
                        LIMIT :batchSize
                        """)
                .setParameter("cutoff", cutoff)
                .setParameter("batchSize", batchSize)
                .getResultList();
        if (ids.isEmpty()) {
            return new IncidentDeletionResult(0, 0);
        }

        long pendingDeliveries = ((Number) entityManager.createNativeQuery("""
                        SELECT count(*) FROM notification_delivery
                        WHERE status = 'PENDING' AND incident_id IN (:ids)
                        """)
                .setParameter("ids", ids)
                .getSingleResult()).longValue();

        int deleted = entityManager.createNativeQuery("DELETE FROM incident WHERE id IN (:ids)")
                .setParameter("ids", ids)
                .executeUpdate();
        return new IncidentDeletionResult(deleted, pendingDeliveries);
    }

    public record IncidentDeletionResult(int incidentsDeleted, long pendingDeliveriesDeleted) {
    }
}
