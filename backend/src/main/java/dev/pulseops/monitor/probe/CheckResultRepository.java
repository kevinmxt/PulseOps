package dev.pulseops.monitor.probe;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CheckResultRepository extends JpaRepository<CheckResultEntity, Long> {
}
