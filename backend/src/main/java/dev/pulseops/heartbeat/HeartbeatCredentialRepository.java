package dev.pulseops.heartbeat;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface HeartbeatCredentialRepository extends JpaRepository<HeartbeatCredentialEntity, Long> {

    @Query("""
            select c.monitorId
            from HeartbeatCredentialEntity c
            where c.tokenHash = :tokenHash
              and c.revokedAt is null
            """)
    Optional<Long> findActiveMonitorIdByTokenHash(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from HeartbeatCredentialEntity c where c.tokenHash = :tokenHash")
    Optional<HeartbeatCredentialEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select c
            from HeartbeatCredentialEntity c
            where c.monitorId = :monitorId
              and c.revokedAt is null
            """)
    Optional<HeartbeatCredentialEntity> findActiveByMonitorIdForUpdate(@Param("monitorId") Long monitorId);
}
