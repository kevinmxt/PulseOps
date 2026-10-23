package dev.pulseops.incident;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.probe.CheckFailureType;
import dev.pulseops.monitor.probe.ProbeOutcome;
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
    private final MeterRegistry meterRegistry;

    public IncidentService(IncidentRepository incidentRepository, MeterRegistry meterRegistry) {
        this.incidentRepository = incidentRepository;
        this.meterRegistry = meterRegistry;
    }

    public void evaluate(MonitorEntity monitor, ProbeOutcome outcome) {
        if (!outcome.success() && monitor.getConsecutiveFailures() >= monitor.getFailureThreshold()) {
            openIfNeeded(monitor, outcome);
        } else if (monitor.getCurrentStatus() == MonitorStatus.UP) {
            closeOpenIncident(monitor.getId(), outcome.finishedAt());
        }
    }

    public void closeOpenIncident(Long monitorId, Instant resolvedAt) {
        incidentRepository.findFirstByMonitorIdAndResolvedAtIsNull(monitorId).ifPresent(incident -> {
            incident.resolve(resolvedAt);
            incidentRepository.save(incident);
            meterRegistry.counter("incident.resolved").increment();
            log.info("Incident resolved, incidentId={}, monitorId={}",
                    incident.getId(), monitorId);
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
    }

    private static String summaryFor(int consecutiveFailures, ProbeOutcome outcome) {
        String summary = "连续 " + consecutiveFailures + " 次探测失败，最新失败："
                + failureLabel(outcome.failureType());
        if (outcome.errorSummary() != null && !outcome.errorSummary().isBlank()) {
            summary += "（" + outcome.errorSummary() + "）";
        }
        return summary.length() <= SUMMARY_MAX_LENGTH
                ? summary
                : summary.substring(0, SUMMARY_MAX_LENGTH);
    }

    private static String failureLabel(CheckFailureType failureType) {
        if (failureType == null) {
            return "未知失败";
        }
        return switch (failureType) {
            case CONNECTION_FAILED -> "连接失败";
            case DNS_RESOLVER_EXHAUSTED -> "DNS 解析器不可用";
            case CONNECT_TIMEOUT -> "连接超时";
            case RESPONSE_TIMEOUT -> "响应超时";
            case UNEXPECTED_STATUS -> "非预期状态码";
            case INTERNAL_ERROR -> "内部探测异常";
        };
    }
}
