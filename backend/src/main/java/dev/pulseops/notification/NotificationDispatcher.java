package dev.pulseops.notification;

import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentRepository;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int ERROR_SUMMARY_MAX_LENGTH = 500;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_RETRY_DELAY = Duration.ofMinutes(1);
    private static final Duration SECOND_RETRY_DELAY = Duration.ofMinutes(5);

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
        if (!deliveryRepository.existsById(delivery.getId())) {
            log.debug("Notification delivery no longer exists, skipping, deliveryId={}",
                    delivery.getId());
            return;
        }

        delivery.recordAttempt(clock.instant());
        try {
            IncidentEntity incident = incidentRepository.findById(delivery.getIncidentId())
                    .orElseThrow(() -> new NotificationSendException("Incident no longer exists", false));
            MonitorEntity monitor = monitorRepository.findById(incident.getMonitorId())
                    .orElseThrow(() -> new NotificationSendException("Monitor no longer exists", false));
            sender.send(messageFormatter.format(delivery.getEventType(), incident, monitor));
            delivery.markSent(clock.instant());
            meterRegistry.counter("notification.sent").increment();
        } catch (NotificationSendException failure) {
            handleFailure(delivery, failure.getMessage(), failure.isRetryable());
        } catch (RuntimeException failure) {
            handleFailure(delivery, failure.getMessage(), false);
        }
        saveOutcomeIfDeliveryStillExists(delivery);
    }

    private void saveOutcomeIfDeliveryStillExists(NotificationDeliveryEntity delivery) {
        try {
            if (!deliveryRepository.existsById(delivery.getId())) {
                log.debug("Notification delivery was deleted during dispatch, skipping update, "
                        + "deliveryId={}", delivery.getId());
                return;
            }
            deliveryRepository.save(delivery);
        } catch (DataIntegrityViolationException | EntityNotFoundException failure) {
            if (deliveryRepository.existsById(delivery.getId())) {
                throw failure;
            }
            log.debug("Notification delivery disappeared during dispatch, skipping update, "
                    + "deliveryId={}", delivery.getId());
        }
    }

    private void handleFailure(NotificationDeliveryEntity delivery, String message,
                               boolean retryable) {
        if (retryable && delivery.getAttemptCount() < MAX_ATTEMPTS) {
            Duration delay = delivery.getAttemptCount() == 1
                    ? FIRST_RETRY_DELAY
                    : SECOND_RETRY_DELAY;
            delivery.scheduleRetry(clock.instant().plus(delay),
                    truncate(sender.sanitize(message)));
            log.debug("Notification retry scheduled, deliveryId={}, attemptCount={}, nextAttemptAt={}",
                    delivery.getId(), delivery.getAttemptCount(), delivery.getNextAttemptAt());
            return;
        }

        delivery.markFailed(truncate(sender.sanitize(message)));
        meterRegistry.counter("notification.failed").increment();
        log.warn("Notification delivery failed, deliveryId={}, channel={}, attemptCount={}",
                delivery.getId(), delivery.getChannel(), delivery.getAttemptCount());
    }

    private static String truncate(String summary) {
        if (summary == null || summary.length() <= ERROR_SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, ERROR_SUMMARY_MAX_LENGTH);
    }
}
