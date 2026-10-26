package dev.pulseops.cleanup;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pulseops.cleanup", name = "scheduling-enabled",
        havingValue = "true", matchIfMissing = true)
class ScheduledCleanupTicker {

    private final CleanupService cleanupService;

    ScheduledCleanupTicker(CleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @Scheduled(fixedDelayString = "${pulseops.cleanup.tick-interval:1h}",
            scheduler = "cleanupTickScheduler")
    void tick() {
        cleanupService.tick();
    }
}
