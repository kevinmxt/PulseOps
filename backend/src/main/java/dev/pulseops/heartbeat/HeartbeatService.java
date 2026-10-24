package dev.pulseops.heartbeat;

import dev.pulseops.incident.IncidentService;
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
    private final IncidentService incidentService;
    private final Clock clock;

    public HeartbeatService(HeartbeatCredentialRepository credentialRepository,
                            MonitorRepository monitorRepository,
                            CheckResultRepository checkResultRepository,
                            HeartbeatTokenGenerator tokenGenerator,
                            IncidentService incidentService,
                            Clock clock) {
        this.credentialRepository = credentialRepository;
        this.monitorRepository = monitorRepository;
        this.checkResultRepository = checkResultRepository;
        this.tokenGenerator = tokenGenerator;
        this.incidentService = incidentService;
        this.clock = clock;
    }

    @Transactional
    public void report(String token) {
        String tokenHash = tokenGenerator.digest(token);

        Long monitorId = credentialRepository.findActiveMonitorIdByTokenHash(tokenHash)
                .orElseThrow(HeartbeatNotFoundException::new);
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(monitorId)
                .orElseThrow(HeartbeatNotFoundException::new);
        credentialRepository.findByTokenHashForUpdate(tokenHash)
                .filter(credential -> credential.getRevokedAt() == null)
                .orElseThrow(HeartbeatNotFoundException::new);

        if (monitor.getType() != MonitorType.HEARTBEAT || !monitor.isEnabled()) {
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

        incidentService.resolveOpenIncident(monitor.getId(), now);
    }

    @Transactional
    public String rotateToken(Long monitorId) {
        lockHeartbeatMonitor(monitorId);

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
        lockHeartbeatMonitor(monitorId);

        credentialRepository.findActiveByMonitorIdForUpdate(monitorId)
                .ifPresent(credential -> credential.setRevokedAt(clock.instant()));
    }

    private MonitorEntity lockHeartbeatMonitor(Long monitorId) {
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        if (monitor.getType() != MonitorType.HEARTBEAT) {
            throw new NotHeartbeatMonitorException();
        }
        return monitor;
    }
}
