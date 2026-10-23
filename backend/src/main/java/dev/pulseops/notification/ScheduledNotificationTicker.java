package dev.pulseops.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pulseops.notification.scheduling-enabled", matchIfMissing = true)
public class ScheduledNotificationTicker {

    private final NotificationDispatcher notificationDispatcher;

    public ScheduledNotificationTicker(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @Scheduled(fixedDelayString = "${pulseops.notification.dispatch-interval:5s}")
    public void tick() {
        notificationDispatcher.dispatchDue();
    }
}
