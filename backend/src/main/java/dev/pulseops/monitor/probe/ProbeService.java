package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorDisabledException;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.MonitorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

@Service
public class ProbeService {

    private final MonitorRepository monitorRepository;
    private final CheckResultRepository checkResultRepository;
    private final HttpProbeExecutor executor;
    private final TransactionTemplate transactionTemplate;

    public ProbeService(MonitorRepository monitorRepository,
                        CheckResultRepository checkResultRepository,
                        HttpProbeExecutor executor,
                        PlatformTransactionManager transactionManager) {
        this.monitorRepository = monitorRepository;
        this.checkResultRepository = checkResultRepository;
        this.executor = executor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Optional<CheckResultEntity> probe(Long monitorId) {
        MonitorEntity monitor = monitorRepository.findById(monitorId)
                .orElseThrow(() -> new MonitorNotFoundException(monitorId));
        if (!monitor.isEnabled()) {
            throw new MonitorDisabledException(monitorId);
        }
        ProbeSnapshot snapshot = new ProbeSnapshot(monitorId, monitor.getConfigVersion(),
                monitor.getIntervalSeconds());
        ProbeOutcome outcome = executor.execute(monitor);
        return transactionTemplate.execute(status -> persist(snapshot, outcome));
    }

    private Optional<CheckResultEntity> persist(ProbeSnapshot snapshot, ProbeOutcome outcome) {
        Optional<MonitorEntity> locked = monitorRepository.findByIdForUpdate(snapshot.monitorId());
        if (locked.isEmpty() || locked.get().getConfigVersion() != snapshot.configVersion()) {
            // The monitor was deleted or reconfigured while the probe was in flight.
            return Optional.empty();
        }

        MonitorEntity monitor = locked.get();
        monitor.recordProbeOutcome(outcome.success());
        monitor.setLastCheckedAt(outcome.finishedAt());
        if (outcome.success()) {
            monitor.setLastSuccessAt(outcome.finishedAt());
        }
        monitor.setNextCheckAt(outcome.finishedAt().plusSeconds(snapshot.intervalSeconds()));

        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(snapshot.monitorId());
        result.setStartedAt(outcome.startedAt());
        result.setFinishedAt(outcome.finishedAt());
        result.setDurationMs(outcome.durationMs());
        result.setSuccess(outcome.success());
        result.setFailureType(outcome.failureType());
        result.setHttpStatusCode(outcome.httpStatusCode());
        result.setErrorSummary(outcome.errorSummary());
        return Optional.of(checkResultRepository.save(result));
    }

    private record ProbeSnapshot(Long monitorId, long configVersion, int intervalSeconds) {
    }
}
