package dev.pulseops.notification;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int ERROR_SUMMARY_MAX_LENGTH = 500;

    private final NotificationDeliveryRepository deliveryRepository;
    private final IncidentRepository incidentRepository;
    private final MonitorRepository monitorRepository;
    private final NotificationMessageFormatter messageFormatter;
    private final TelegramNotificationSender sender;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final int batchSize;

    public NotificationDispatcher(NotificationDeliveryRepository deliveryRepository,
                                  IncidentRepository incidentRepository,
                                  MonitorRepository monitorRepository,
                                  NotificationMessageFormatter messageFormatter,
                                  TelegramNotificationSender sender,
                                  MeterRegistry meterRegistry,
                                  Clock clock,
                                  NotificationProperties notificationProperties) {
        this.deliveryRepository = deliveryRepository;
        this.incidentRepository = incidentRepository;
        this.monitorRepository = monitorRepository;
        this.messageFormatter = messageFormatter;
        this.sender = sender;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.batchSize = notificationProperties.batchSize();
    }

    public void dispatchDue() {
        Instant now = clock.instant();
        List<NotificationDeliveryEntity> due =
                deliveryRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                        NotificationDeliveryStatus.PENDING, now, PageRequest.of(0, batchSize));
        due.forEach(this::dispatch);
    }

    private void dispatch(NotificationDeliveryEntity delivery) {
        delivery.recordAttempt(clock.instant());
        try {
            IncidentEntity incident = incidentRepository.findById(delivery.getIncidentId())
                    .orElseThrow(() -> new NotificationSendException("Incident no longer exists"));
            MonitorEntity monitor = monitorRepository.findById(incident.getMonitorId())
                    .orElseThrow(() -> new NotificationSendException("Monitor no longer exists"));
            sender.send(messageFormatter.format(delivery.getEventType(), incident, monitor));
            delivery.markSent(clock.instant());
            meterRegistry.counter("notification.sent").increment();
        } catch (RuntimeException failure) {
            delivery.markFailed(truncate(sender.sanitize(failure.getMessage())));
            meterRegistry.counter("notification.failed").increment();
            log.warn("Notification delivery failed, deliveryId={}, channel={}, attemptCount={}",
                    delivery.getId(), delivery.getChannel(), delivery.getAttemptCount());
        }
        deliveryRepository.save(delivery);
    }

    private static String truncate(String summary) {
        if (summary == null || summary.length() <= ERROR_SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, ERROR_SUMMARY_MAX_LENGTH);
    }
}
