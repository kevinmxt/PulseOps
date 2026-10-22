package dev.pulseops.monitor.probe;

import dev.pulseops.common.PageQueries;
import dev.pulseops.monitor.MonitorNotFoundException;
import dev.pulseops.monitor.MonitorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CheckResultQueryService {

    private final MonitorRepository monitorRepository;
    private final CheckResultRepository checkResultRepository;

    public CheckResultQueryService(MonitorRepository monitorRepository,
                                   CheckResultRepository checkResultRepository) {
        this.monitorRepository = monitorRepository;
        this.checkResultRepository = checkResultRepository;
    }

    @Transactional(readOnly = true)
    public Page<CheckResultEntity> listByMonitor(Long monitorId, int page, int size) {
        if (!monitorRepository.existsById(monitorId)) {
            throw new MonitorNotFoundException(monitorId);
        }
        PageRequest pageRequest = PageQueries.of(page, size,
                Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));
        return checkResultRepository.findByMonitorId(monitorId, pageRequest);
    }
}
