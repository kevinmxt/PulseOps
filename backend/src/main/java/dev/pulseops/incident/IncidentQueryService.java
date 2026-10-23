package dev.pulseops.incident;

import dev.pulseops.common.PageQueries;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.notification.NotificationDeliveryEntity;
import dev.pulseops.notification.NotificationDeliveryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class IncidentQueryService {

    private final IncidentRepository incidentRepository;
    private final MonitorRepository monitorRepository;
    private final NotificationDeliveryRepository notificationDeliveryRepository;

    public IncidentQueryService(IncidentRepository incidentRepository,
                                MonitorRepository monitorRepository,
                                NotificationDeliveryRepository notificationDeliveryRepository) {
        this.incidentRepository = incidentRepository;
        this.monitorRepository = monitorRepository;
        this.notificationDeliveryRepository = notificationDeliveryRepository;
    }

    @Transactional(readOnly = true)
    public Page<IncidentEntity> list(IncidentStatus status, Long monitorId, int page, int size) {
        PageRequest pageRequest = PageQueries.of(page, size,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        return incidentRepository.search(status, monitorId, pageRequest);
    }

    @Transactional(readOnly = true)
    public IncidentEntity get(Long id) {
        return incidentRepository.findById(id)
                .orElseThrow(() -> new IncidentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Map<Long, String> monitorNames(Collection<Long> monitorIds) {
        return monitorRepository.findAllById(monitorIds).stream()
                .collect(Collectors.toMap(MonitorEntity::getId, MonitorEntity::getName));
    }

    @Transactional(readOnly = true)
    public String monitorName(Long monitorId) {
        return monitorRepository.findById(monitorId)
                .map(MonitorEntity::getName)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<NotificationDeliveryEntity> recentDeliveries(Long incidentId) {
        return notificationDeliveryRepository.findByIncidentIdOrderByIdDesc(
                incidentId, PageRequest.of(0, 5));
    }
}
