package dev.pulseops.notification;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDeliveryEntity, Long> {

    List<NotificationDeliveryEntity> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
            NotificationDeliveryStatus status, Instant now, Pageable pageable);

    List<NotificationDeliveryEntity> findByIncidentIdOrderByIdDesc(Long incidentId, Pageable pageable);

    long countByStatus(NotificationDeliveryStatus status);
}
