package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorDisabledException;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
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
        ProbeOutcome outcome = executor.execute(monitor);
        return transactionTemplate.execute(status -> persist(monitorId, outcome));
    }

    private Optional<CheckResultEntity> persist(Long monitorId, ProbeOutcome outcome) {
        MonitorEntity monitor = monitorRepository.findById(monitorId).orElse(null);
        if (monitor == null) {
            return Optional.empty();
        }
        Instant nextCheckAt = outcome.finishedAt().plusSeconds(monitor.getIntervalSeconds());
        MonitorStatus status = outcome.success() ? MonitorStatus.UP : MonitorStatus.DOWN;
        Instant successAt = outcome.success() ? outcome.finishedAt() : null;

        int updated = monitorRepository.updateProbeStatus(
                monitorId, status, outcome.finishedAt(), successAt, nextCheckAt);
        if (updated == 0) {
            // The monitor was deleted while the probe was in flight; discard the result.
            return Optional.empty();
        }

        CheckResultEntity result = new CheckResultEntity();
        result.setMonitorId(monitorId);
        result.setStartedAt(outcome.startedAt());
        result.setFinishedAt(outcome.finishedAt());
        result.setDurationMs(outcome.durationMs());
        result.setSuccess(outcome.success());
        result.setFailureType(outcome.failureType());
        result.setHttpStatusCode(outcome.httpStatusCode());
        result.setErrorSummary(outcome.errorSummary());
        return Optional.of(checkResultRepository.save(result));
    }
}
