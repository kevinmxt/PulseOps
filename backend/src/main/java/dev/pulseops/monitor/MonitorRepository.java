package dev.pulseops.monitor;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MonitorRepository extends JpaRepository<MonitorEntity, Long> {

    @Query("""
            select m.id
            from MonitorEntity m
            where m.enabled = true
              and m.nextCheckAt <= :now
            order by m.nextCheckAt asc, m.id asc
            """)
    List<Long> findDueMonitorIds(@Param("now") Instant now, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MonitorEntity m
            set m.currentStatus = :status,
                m.lastCheckedAt = :checkedAt,
                m.lastSuccessAt = coalesce(:successAt, m.lastSuccessAt),
                m.nextCheckAt = :nextCheckAt
            where m.id = :id
              and m.configVersion = :expectedVersion
            """)
    int updateProbeStatus(@Param("id") Long id,
                          @Param("expectedVersion") long expectedVersion,
                          @Param("status") MonitorStatus status,
                          @Param("checkedAt") Instant checkedAt,
                          @Param("successAt") Instant successAt,
                          @Param("nextCheckAt") Instant nextCheckAt);
}
