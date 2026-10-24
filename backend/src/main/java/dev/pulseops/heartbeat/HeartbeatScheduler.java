package dev.pulseops.heartbeat;

import dev.pulseops.monitor.MonitorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final MonitorRepository monitorRepository;
    private final HeartbeatTimeoutService timeoutService;
    private final Clock clock;
    private final int batchSize;

    public HeartbeatScheduler(MonitorRepository monitorRepository,
                              HeartbeatTimeoutService timeoutService,
                              Clock clock,
                              HeartbeatProperties properties) {
        this.monitorRepository = monitorRepository;
        this.timeoutService = timeoutService;
        this.clock = clock;
        this.batchSize = properties.batchSize();
    }

    public void tick() {
        Instant now = clock.instant();
        for (Long monitorId : monitorRepository.findDueHeartbeatIds(now, PageRequest.of(0, batchSize))) {
            try {
                timeoutService.checkTimeout(monitorId);
            } catch (RuntimeException e) {
                log.error("Scheduled heartbeat check failed for monitor {}", monitorId, e);
            }
        }
    }
}
