package dev.pulseops.monitor.probe;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CheckResultRepository extends JpaRepository<CheckResultEntity, Long> {

    Page<CheckResultEntity> findByMonitorId(Long monitorId, Pageable pageable);

    @Query(value = """
            SELECT DISTINCT ON (monitor_id) monitor_id AS "monitorId", duration_ms AS "durationMs"
            FROM check_result
            WHERE monitor_id IN (:monitorIds)
            ORDER BY monitor_id, started_at DESC, id DESC
            """, nativeQuery = true)
    List<LatestCheckDuration> findLatestDurations(@Param("monitorIds") Collection<Long> monitorIds);

    interface LatestCheckDuration {
        Long getMonitorId();

        long getDurationMs();
    }
}
