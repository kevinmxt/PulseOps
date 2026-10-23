package dev.pulseops.incident;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.probe.ProbeOutcome;
import dev.pulseops.notification.NotificationEventType;
import dev.pulseops.notification.NotificationOutbox;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);
    private static final int SUMMARY_MAX_LENGTH = 200;

    private final IncidentRepository incidentRepository;
    private final NotificationOutbox notificationOutbox;
    private final MeterRegistry meterRegistry;

    public IncidentService(IncidentRepository incidentRepository,
                           NotificationOutbox notificationOutbox,
                           MeterRegistry meterRegistry) {
        this.incidentRepository = incidentRepository;
        this.notificationOutbox = notificationOutbox;
        this.meterRegistry = meterRegistry;
    }

    public void evaluate(MonitorEntity monitor, ProbeOutcome outcome) {
        if (!outcome.success() && monitor.getConsecutiveFailures() >= monitor.getFailureThreshold()) {
            openIfNeeded(monitor, outcome);
        } else if (monitor.getCurrentStatus() == MonitorStatus.UP) {
            resolveOpenIncident(monitor.getId(), outcome.finishedAt());
        }
    }

    public void resolveOpenIncident(Long monitorId, Instant resolvedAt) {
        closeOpenIncident(monitorId, resolvedAt).ifPresent(incident ->
                notificationOutbox.enqueue(incident.getId(), NotificationEventType.INCIDENT_RESOLVED));
    }

    public void closeOpenIncidentSilently(Long monitorId, Instant resolvedAt) {
        closeOpenIncident(monitorId, resolvedAt);
    }

    private java.util.Optional<IncidentEntity> closeOpenIncident(Long monitorId, Instant resolvedAt) {
        return incidentRepository.findFirstByMonitorIdAndResolvedAtIsNull(monitorId).map(incident -> {
            incident.resolve(resolvedAt);
            IncidentEntity saved = incidentRepository.save(incident);
            meterRegistry.counter("incident.resolved").increment();
            log.info("Incident resolved, incidentId={}, monitorId={}",
                    saved.getId(), monitorId);
            return saved;
        });
    }

    private void openIfNeeded(MonitorEntity monitor, ProbeOutcome outcome) {
        if (incidentRepository.existsByMonitorIdAndResolvedAtIsNull(monitor.getId())) {
            return;
        }

        IncidentEntity incident = new IncidentEntity();
        incident.setMonitorId(monitor.getId());
        incident.setStartedAt(outcome.finishedAt());
        incident.setStatus(IncidentStatus.OPEN);
        incident.setFailureType(outcome.failureType());
        incident.setSummary(summaryFor(monitor.getConsecutiveFailures(), outcome));
        IncidentEntity saved = incidentRepository.save(incident);

        meterRegistry.counter("incident.opened").increment();
        log.info("Incident opened, incidentId={}, monitorId={}, failureType={}",
                saved.getId(), saved.getMonitorId(), saved.getFailureType());
        notificationOutbox.enqueue(saved.getId(), NotificationEventType.INCIDENT_OPENED);
    }

    private static String summaryFor(int consecutiveFailures, ProbeOutcome outcome) {
        String summary = "连续 " + consecutiveFailures + " 次探测失败，最新失败："
                + (outcome.failureType() == null ? "未知失败" : outcome.failureType().label());
        if (outcome.errorSummary() != null && !outcome.errorSummary().isBlank()) {
            summary += "（" + outcome.errorSummary() + "）";
        }
        return summary.length() <= SUMMARY_MAX_LENGTH
                ? summary
                : summary.substring(0, SUMMARY_MAX_LENGTH);
    }
}
