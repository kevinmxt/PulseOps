package dev.pulseops.monitor;

import dev.pulseops.common.PageQueries;
import dev.pulseops.heartbeat.HeartbeatCredentialEntity;
import dev.pulseops.heartbeat.HeartbeatCredentialRepository;
import dev.pulseops.heartbeat.HeartbeatTokenGenerator;
import dev.pulseops.incident.IncidentService;
import dev.pulseops.monitor.config.HeartbeatConfig;
import dev.pulseops.monitor.config.InvalidMonitorConfigException;
import dev.pulseops.monitor.config.MonitorConfig;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.config.MonitorConfigValidator;
import dev.pulseops.monitor.probe.CheckResultRepository;
import dev.pulseops.monitor.target.TargetPolicy;
import dev.pulseops.monitor.target.TargetResolutionException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class MonitorService {

    private final MonitorRepository repository;
    private final MonitorConfigValidator configValidator;
    private final TargetPolicy targetPolicy;
    private final IncidentService incidentService;
    private final HeartbeatCredentialRepository heartbeatCredentialRepository;
    private final HeartbeatTokenGenerator heartbeatTokenGenerator;
    private final CheckResultRepository checkResultRepository;
    private final Clock clock;

    public MonitorService(MonitorRepository repository, MonitorConfigValidator configValidator,
                          TargetPolicy targetPolicy, IncidentService incidentService,
                          HeartbeatCredentialRepository heartbeatCredentialRepository,
                          HeartbeatTokenGenerator heartbeatTokenGenerator,
                          CheckResultRepository checkResultRepository, Clock clock) {
        this.repository = repository;
        this.configValidator = configValidator;
        this.targetPolicy = targetPolicy;
        this.incidentService = incidentService;
        this.heartbeatCredentialRepository = heartbeatCredentialRepository;
        this.heartbeatTokenGenerator = heartbeatTokenGenerator;
        this.checkResultRepository = checkResultRepository;
        this.clock = clock;
    }

    @Transactional
    public MonitorCreationResult create(MonitorConfigInput input) {
        return switch (configValidator.parseType(input.type())) {
            case HTTP -> new MonitorCreationResult(createHttp(input), null);
            case HEARTBEAT -> createHeartbeat(input);
        };
    }

    private MonitorEntity createHttp(MonitorConfigInput input) {
        MonitorConfig config = configValidator.validate(input);
        validateTarget(config.targetUrl());

        Instant now = clock.instant();
        MonitorEntity monitor = new MonitorEntity();
        applyConfig(monitor, config);
        monitor.setType(MonitorType.HTTP);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return repository.save(monitor);
    }

    private MonitorCreationResult createHeartbeat(MonitorConfigInput input) {
        HeartbeatConfig config = configValidator.validateHeartbeat(input);

        Instant now = clock.instant();
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName(config.name());
        monitor.setType(MonitorType.HEARTBEAT);
        monitor.setIntervalSeconds(config.intervalSeconds());
        monitor.setHeartbeatGraceSeconds(config.graceSeconds());
        monitor.setEnabled(config.enabled());
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        monitor.setNextCheckAt(monitor.heartbeatDeadline());
        monitor = repository.save(monitor);

        String token = heartbeatTokenGenerator.generate();
        HeartbeatCredentialEntity credential = new HeartbeatCredentialEntity();
        credential.setMonitorId(monitor.getId());
        credential.setTokenHash(heartbeatTokenGenerator.digest(token));
        credential.setCreatedAt(now);
        heartbeatCredentialRepository.save(credential);

        return new MonitorCreationResult(monitor, token);
    }

    @Transactional(readOnly = true)
    public MonitorEntity get(Long id) {
        return repository.findById(id).orElseThrow(() -> new MonitorNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<MonitorEntity> list(int page, int size) {
        PageRequest pageRequest = PageQueries.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return repository.findAll(pageRequest);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> latestDurations(Collection<Long> monitorIds) {
        if (monitorIds.isEmpty()) {
            return Map.of();
        }
        return checkResultRepository.findLatestDurations(monitorIds).stream()
                .collect(Collectors.toMap(CheckResultRepository.LatestCheckDuration::getMonitorId,
                        CheckResultRepository.LatestCheckDuration::getDurationMs));
    }

    @Transactional(readOnly = true)
    public Long latestDuration(Long monitorId) {
        return latestDurations(List.of(monitorId)).get(monitorId);
    }

    @Transactional
    public MonitorEntity update(Long id, MonitorConfigInput input) {
        MonitorEntity monitor = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new MonitorNotFoundException(id));
        MonitorType requestedType = input.type() == null || input.type().isBlank()
                ? monitor.getType()
                : configValidator.parseType(input.type());
        if (requestedType != monitor.getType()) {
            throw InvalidMonitorConfigException.validation("type", "cannot be changed after creation");
        }
        return monitor.getType() == MonitorType.HTTP
                ? updateHttp(monitor, input)
                : updateHeartbeat(monitor, input);
    }

    private MonitorEntity updateHttp(MonitorEntity monitor, MonitorConfigInput input) {
        MonitorConfig config = configValidator.validate(input);
        validateTarget(config.targetUrl());

        boolean wasEnabled = monitor.isEnabled();
        boolean semanticsChanged = probeSemanticsChanged(monitor, config);
        boolean becameEnabled = !wasEnabled && config.enabled();
        boolean becameDisabled = wasEnabled && !config.enabled();
        boolean needsImmediateCheck = becameEnabled || semanticsChanged;

        applyConfig(monitor, config);
        if (semanticsChanged) {
            monitor.incrementConfigVersion();
            monitor.resetConsecutiveCounters();
        }
        if (becameEnabled) {
            monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
            monitor.resetConsecutiveCounters();
        } else if (becameDisabled) {
            monitor.resetConsecutiveCounters();
            incidentService.closeOpenIncidentSilently(monitor.getId(), clock.instant());
        }
        monitor.setUpdatedAt(clock.instant());
        if (needsImmediateCheck) {
            monitor.setNextCheckAt(monitor.getUpdatedAt());
        }
        return repository.save(monitor);
    }

    private MonitorEntity updateHeartbeat(MonitorEntity monitor, MonitorConfigInput input) {
        HeartbeatConfig config = configValidator.validateHeartbeat(input);

        boolean wasEnabled = monitor.isEnabled();
        boolean becameEnabled = !wasEnabled && config.enabled();
        boolean becameDisabled = wasEnabled && !config.enabled();
        boolean deadlineConfigChanged = monitor.getIntervalSeconds() != config.intervalSeconds()
                || !Objects.equals(monitor.getHeartbeatGraceSeconds(), config.graceSeconds());

        monitor.setName(config.name());
        monitor.setIntervalSeconds(config.intervalSeconds());
        monitor.setHeartbeatGraceSeconds(config.graceSeconds());
        monitor.setEnabled(config.enabled());
        if (becameEnabled) {
            monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        } else if (becameDisabled) {
            incidentService.closeOpenIncidentSilently(monitor.getId(), clock.instant());
        }
        monitor.setUpdatedAt(clock.instant());
        if (becameEnabled) {
            monitor.setNextCheckAt(monitor.getUpdatedAt().plusSeconds(
                    config.intervalSeconds() + config.graceSeconds()));
        } else if (deadlineConfigChanged) {
            monitor.setNextCheckAt(monitor.heartbeatDeadline());
        }
        return repository.save(monitor);
    }

    @Transactional
    public void delete(Long id) {
        MonitorEntity monitor = get(id);
        repository.delete(monitor);
    }

    private void validateTarget(URI target) {
        try {
            targetPolicy.validate(target);
        } catch (TargetResolutionException ignored) {
            // Specified behaviour: a temporary DNS failure must not block saving.
            // The Probe re-validates the address and classifies the failure.
        }
    }

    private static boolean probeSemanticsChanged(MonitorEntity monitor, MonitorConfig config) {
        return !monitor.getTargetUrl().equals(config.targetUrl().toString())
                || monitor.getMethod() != config.method()
                || monitor.getIntervalSeconds() != config.intervalSeconds()
                || monitor.getConnectTimeoutMs() != config.connectTimeoutMs()
                || monitor.getResponseTimeoutMs() != config.responseTimeoutMs()
                || monitor.getExpectedStatusMin() != config.expectedStatusMin()
                || monitor.getExpectedStatusMax() != config.expectedStatusMax();
    }

    private static void applyConfig(MonitorEntity monitor, MonitorConfig config) {
        monitor.setName(config.name());
        monitor.setTargetUrl(config.targetUrl().toString());
        monitor.setMethod(config.method());
        monitor.setIntervalSeconds(config.intervalSeconds());
        monitor.setConnectTimeoutMs(config.connectTimeoutMs());
        monitor.setResponseTimeoutMs(config.responseTimeoutMs());
        monitor.setExpectedStatusMin(config.expectedStatusMin());
        monitor.setExpectedStatusMax(config.expectedStatusMax());
        monitor.setEnabled(config.enabled());
        monitor.setFailureThreshold(config.failureThreshold());
        monitor.setRecoveryThreshold(config.recoveryThreshold());
    }
}
