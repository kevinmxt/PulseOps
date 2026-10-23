package dev.pulseops.notification;

import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class NotificationOutbox {

    private final NotificationDeliveryRepository deliveryRepository;
    private final TelegramProperties telegramProperties;
    private final Clock clock;

    public NotificationOutbox(NotificationDeliveryRepository deliveryRepository,
                              TelegramProperties telegramProperties,
                              Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.telegramProperties = telegramProperties;
        this.clock = clock;
    }

    public void enqueue(Long incidentId, NotificationEventType eventType) {
        if (!telegramProperties.isConfigured()) {
            return;
        }

        NotificationDeliveryEntity delivery = new NotificationDeliveryEntity();
        delivery.setIncidentId(incidentId);
        delivery.setChannel(NotificationChannel.TELEGRAM);
        delivery.setEventType(eventType);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setAttemptCount(0);
        delivery.setNextAttemptAt(clock.instant());
        deliveryRepository.save(delivery);
    }
}
