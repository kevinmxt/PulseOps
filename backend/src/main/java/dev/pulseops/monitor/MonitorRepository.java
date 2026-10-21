package dev.pulseops.monitor;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitorRepository extends JpaRepository<MonitorEntity, Long> {
}
