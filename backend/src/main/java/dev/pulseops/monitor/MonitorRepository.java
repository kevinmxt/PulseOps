package dev.pulseops.monitor;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MonitorRepository extends JpaRepository<MonitorEntity, Long> {

    @Query("""
            select m.id
            from MonitorEntity m
            where m.enabled = true
              and m.type = dev.pulseops.monitor.MonitorType.HTTP
              and m.nextCheckAt <= :now
            order by m.nextCheckAt asc, m.id asc
            """)
    List<Long> findDueMonitorIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select m.id
            from MonitorEntity m
            where m.enabled = true
              and m.type = dev.pulseops.monitor.MonitorType.HEARTBEAT
              and m.nextCheckAt <= :now
            order by m.nextCheckAt asc, m.id asc
            """)
    List<Long> findDueHeartbeatIds(@Param("now") Instant now, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MonitorEntity m where m.id = :id")
    Optional<MonitorEntity> findByIdForUpdate(@Param("id") Long id);
}
