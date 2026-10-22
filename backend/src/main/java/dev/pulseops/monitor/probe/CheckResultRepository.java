package dev.pulseops.monitor.probe;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckResultRepository extends JpaRepository<CheckResultEntity, Long> {

    Page<CheckResultEntity> findByMonitorId(Long monitorId, Pageable pageable);
}
