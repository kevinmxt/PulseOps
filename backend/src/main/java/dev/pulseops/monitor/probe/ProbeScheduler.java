package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorDisabledException;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Component
public class ProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProbeScheduler.class);

    private final MonitorRepository monitorRepository;
    private final ProbeService probeService;
    private final Clock clock;
    private final int batchSize;
    private final Executor executor;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public ProbeScheduler(MonitorRepository monitorRepository,
                          ProbeService probeService,
                          Clock clock,
                          ProbeSchedulingProperties properties,
                          @Qualifier("probeExecutor") Executor executor) {
        this.monitorRepository = monitorRepository;
        this.probeService = probeService;
        this.clock = clock;
        this.batchSize = properties.batchSize();
        this.executor = executor;
    }

    public void tick() {
        Instant now = clock.instant();
        List<Long> dueMonitorIds = monitorRepository.findDueMonitorIds(now, PageRequest.of(0, batchSize));
        int rejected = 0;
        for (Long monitorId : dueMonitorIds) {
            if (!inFlight.add(monitorId)) {
                continue;
            }
            try {
                executor.execute(() -> runProbe(monitorId));
            } catch (RejectedExecutionException e) {
                inFlight.remove(monitorId);
                rejected++;
            }
        }
        if (rejected > 0) {
            log.warn("Skipped {} due probe(s): probe executor rejected them", rejected);
        }
    }

    boolean isInFlight(Long monitorId) {
        return inFlight.contains(monitorId);
    }

    private void runProbe(Long monitorId) {
        try {
            probeService.probe(monitorId);
        } catch (MonitorNotFoundException | MonitorDisabledException e) {
            log.debug("Skipping probe for monitor {}: {}", monitorId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Scheduled probe failed for monitor {}", monitorId, e);
        } finally {
            inFlight.remove(monitorId);
        }
    }
}
