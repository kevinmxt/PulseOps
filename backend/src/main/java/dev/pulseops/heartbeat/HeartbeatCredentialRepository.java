package dev.pulseops.heartbeat;

import org.springframework.data.jpa.repository.JpaRepository;

public interface HeartbeatCredentialRepository extends JpaRepository<HeartbeatCredentialEntity, Long> {
}
