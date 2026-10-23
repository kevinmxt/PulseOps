package dev.pulseops.incident;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface IncidentRepository extends JpaRepository<IncidentEntity, Long> {

    boolean existsByMonitorIdAndResolvedAtIsNull(Long monitorId);

    Optional<IncidentEntity> findFirstByMonitorIdAndResolvedAtIsNull(Long monitorId);

    @Query("""
            select i
            from IncidentEntity i
            where (:status is null or i.status = :status)
              and (:monitorId is null or i.monitorId = :monitorId)
            """)
    Page<IncidentEntity> search(@Param("status") IncidentStatus status,
                                @Param("monitorId") Long monitorId,
                                Pageable pageable);
}
