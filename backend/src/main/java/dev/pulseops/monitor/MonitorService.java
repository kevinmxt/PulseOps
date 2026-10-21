package dev.pulseops.monitor;

import dev.pulseops.common.InvalidQueryException;
import dev.pulseops.monitor.config.MonitorConfig;
import dev.pulseops.monitor.config.MonitorConfigInput;
import dev.pulseops.monitor.config.MonitorConfigValidator;
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

@Service
public class MonitorService {

    static final int MAX_PAGE_SIZE = 100;

    private final MonitorRepository repository;
    private final MonitorConfigValidator configValidator;
    private final TargetPolicy targetPolicy;
    private final Clock clock;

    public MonitorService(MonitorRepository repository, MonitorConfigValidator configValidator,
                          TargetPolicy targetPolicy, Clock clock) {
        this.repository = repository;
        this.configValidator = configValidator;
        this.targetPolicy = targetPolicy;
        this.clock = clock;
    }

    @Transactional
    public MonitorEntity create(MonitorConfigInput input) {
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

    @Transactional(readOnly = true)
    public MonitorEntity get(Long id) {
        return repository.findById(id).orElseThrow(() -> new MonitorNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<MonitorEntity> list(int page, int size) {
        if (page < 0) {
            throw new InvalidQueryException("page must be zero or greater");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidQueryException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return repository.findAll(pageRequest);
    }

    @Transactional
    public MonitorEntity update(Long id, MonitorConfigInput input) {
        MonitorEntity monitor = get(id);
        MonitorConfig config = configValidator.validate(input);
        validateTarget(config.targetUrl());

        boolean becameEnabled = !monitor.isEnabled() && config.enabled();
        boolean intervalChanged = monitor.getIntervalSeconds() != config.intervalSeconds();
        boolean targetChanged = !monitor.getTargetUrl().equals(config.targetUrl().toString());
        boolean needsImmediateCheck = becameEnabled || intervalChanged || targetChanged;

        applyConfig(monitor, config);
        monitor.setUpdatedAt(clock.instant());
        if (needsImmediateCheck) {
            monitor.setNextCheckAt(monitor.getUpdatedAt());
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
    }
}
