package dev.pulseops.heartbeat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pulseops.heartbeat", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
class ScheduledHeartbeatTicker {

    private final HeartbeatScheduler heartbeatScheduler;

    ScheduledHeartbeatTicker(HeartbeatScheduler heartbeatScheduler) {
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @Scheduled(fixedDelayString = "${pulseops.heartbeat.tick-interval:5s}",
            scheduler = "heartbeatTickScheduler")
    void tick() {
        heartbeatScheduler.tick();
    }
}
