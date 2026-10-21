package dev.pulseops.monitor;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface MonitorRepository extends JpaRepository<MonitorEntity, Long> {

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update MonitorEntity m
            set m.currentStatus = :status,
                m.lastCheckedAt = :checkedAt,
                m.lastSuccessAt = coalesce(:successAt, m.lastSuccessAt)
            where m.id = :id
            """)
    int updateProbeStatus(@Param("id") Long id,
                          @Param("status") MonitorStatus status,
                          @Param("checkedAt") Instant checkedAt,
                          @Param("successAt") Instant successAt);
}
