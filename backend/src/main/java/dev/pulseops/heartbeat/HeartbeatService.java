package dev.pulseops.heartbeat;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.probe.CheckResultEntity;
import dev.pulseops.monitor.probe.CheckResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
public class HeartbeatService {

    private final HeartbeatCredentialRepository credentialRepository;
    private final MonitorRepository monitorRepository;
    private final CheckResultRepository checkResultRepository;
    private final HeartbeatTokenGenerator tokenGenerator;
    private final HeartbeatRateLimiter rateLimiter;
    private final Clock clock;

    public HeartbeatService(HeartbeatCredentialRepository credentialRepository,
                            MonitorRepository monitorRepository,
                            CheckResultRepository checkResultRepository,
                            HeartbeatTokenGenerator tokenGenerator,
                            HeartbeatRateLimiter rateLimiter,
                            Clock clock) {
        this.credentialRepository = credentialRepository;
        this.monitorRepository = monitorRepository;
        this.checkResultRepository = checkResultRepository;
        this.tokenGenerator = tokenGenerator;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional
    public void report(String token) {
        String tokenHash = tokenGenerator.digest(token);
        rateLimiter.acquire(tokenHash);

        HeartbeatCredentialEntity credential = credentialRepository.findByTokenHash(tokenHash)
                .filter(candidate -> candidate.getRevokedAt() == null)
                .orElseThrow(HeartbeatNotFoundException::new);
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(credential.getMonitorId())
                .orElseThrow(HeartbeatNotFoundException::new);
        HeartbeatCredentialEntity lockedCredential = credentialRepository.findByTokenHashForUpdate(tokenHash)
                .filter(candidate -> candidate.getRevokedAt() == null)
                .orElseThrow(HeartbeatNotFoundException::new);

        if (monitor.getType() != MonitorType.HEARTBEAT
                || !monitor.isEnabled()
                || !lockedCredential.getMonitorId().equals(monitor.getId())) {
            throw new HeartbeatNotFoundException();
        }

        Instant now = clock.instant();
        monitor.setLastHeartbeatAt(now);
        monitor.setLastCheckedAt(now);
        monitor.setLastSuccessAt(now);
        monitor.setCurrentStatus(MonitorStatus.UP);
        monitor.setNextCheckAt(now.plusSeconds(
                monitor.getIntervalSeconds() + monitor.getHeartbeatGraceSeconds()));
        monitor.setUpdatedAt(now);
        monitorRepository.save(monitor);

        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitor.getId());
        result.setStartedAt(now);
        result.setFinishedAt(now);
        result.setDurationMs(0);
        result.setSuccess(true);
        checkResultRepository.save(result);
    }

    @Transactional
    public String rotateToken(Long monitorId) {
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        requireHeartbeat(monitor);

        Instant now = clock.instant();
        credentialRepository.findActiveByMonitorIdForUpdate(monitorId)
                .ifPresent(credential -> credential.setRevokedAt(now));
        credentialRepository.flush();

        String token = tokenGenerator.generate();
        HeartbeatCredentialEntity credential = new HeartbeatCredentialEntity();
        credential.setMonitorId(monitorId);
        credential.setTokenHash(tokenGenerator.digest(token));
        credential.setCreatedAt(now);
        credentialRepository.save(credential);
        return token;
    }

    @Transactional
    public void revokeToken(Long monitorId) {
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        requireHeartbeat(monitor);

        credentialRepository.findActiveByMonitorIdForUpdate(monitorId)
                .ifPresent(credential -> credential.setRevokedAt(clock.instant()));
    }

    private static void requireHeartbeat(MonitorEntity monitor) {
        if (monitor.getType() != MonitorType.HEARTBEAT) {
            throw new NotHeartbeatMonitorException();
        }
    }
}
