package dev.pulseops.heartbeat;

import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.incident.IncidentService;
import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorEntity;
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
public class HeartbeatTimeoutService {

    private final MonitorRepository monitorRepository;
    private final IncidentRepository incidentRepository;
    private final IncidentService incidentService;
    private final CheckResultRepository checkResultRepository;
    private final Clock clock;

    public HeartbeatTimeoutService(MonitorRepository monitorRepository,
                                   IncidentRepository incidentRepository,
                                   IncidentService incidentService,
                                   CheckResultRepository checkResultRepository,
                                   Clock clock) {
        this.monitorRepository = monitorRepository;
        this.incidentRepository = incidentRepository;
        this.incidentService = incidentService;
        this.checkResultRepository = checkResultRepository;
        this.clock = clock;
    }

    @Transactional
    public void checkTimeout(Long monitorId) {
        MonitorEntity monitor = monitorRepository.findByIdForUpdate(monitorId).orElse(null);
        if (monitor == null || monitor.getType() != MonitorType.HEARTBEAT || !monitor.isEnabled()) {
            return;
        }

        Instant now = clock.instant();
        Instant deadline = monitor.heartbeatDeadline();
        if (now.isBefore(deadline)) {
            monitor.setNextCheckAt(deadline);
            monitorRepository.save(monitor);
            return;
        }

        boolean incidentOpen = incidentRepository.existsByMonitorIdAndResolvedAtIsNull(monitorId);
        monitor.setCurrentStatus(MonitorStatus.DOWN);
        monitor.setLastCheckedAt(now);
        monitor.setNextCheckAt(now.plusSeconds(
                monitor.getIntervalSeconds() + monitor.getHeartbeatGraceSeconds()));
        monitor.setUpdatedAt(now);
        if (!incidentOpen) {
            incidentService.openHeartbeatTimeout(monitor, deadline);
            checkResultRepository.save(timeoutResult(monitorId, now));
        }
        monitorRepository.save(monitor);
    }

    private static CheckResultEntity timeoutResult(Long monitorId, Instant detectedAt) {
        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(detectedAt);
        result.setFinishedAt(detectedAt);
        result.setDurationMs(0);
        result.setSuccess(false);
        result.setFailureType(CheckFailureType.HEARTBEAT_TIMEOUT);
        return result;
    }
}
