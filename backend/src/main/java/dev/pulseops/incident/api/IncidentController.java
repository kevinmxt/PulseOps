package dev.pulseops.incident.api;

import dev.pulseops.common.InvalidQueryException;
import dev.pulseops.common.PageResponse;
import dev.pulseops.incident.IncidentEntity;
import dev.pulseops.incident.IncidentQueryService;
import dev.pulseops.incident.IncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    private final IncidentQueryService incidentQueryService;

    public IncidentController(IncidentQueryService incidentQueryService) {
        this.incidentQueryService = incidentQueryService;
    }

    @GetMapping
    public PageResponse<IncidentResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(name = "monitor_id", required = false) Long monitorId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        IncidentStatus parsedStatus = parseStatus(status);
        Page<IncidentEntity> result = incidentQueryService.list(parsedStatus, monitorId, page, size);
        Map<Long, String> monitorNames = incidentQueryService.monitorNames(
                result.getContent().stream().map(IncidentEntity::getMonitorId).toList());
        List<IncidentResponse> items = result.getContent().stream()
                .map(incident -> IncidentResponse.from(incident,
                        monitorNames.get(incident.getMonitorId())))
                .toList();
        return new PageResponse<>(items, page, size, result.getTotalElements());
    }

    @GetMapping("/{id}")
    public IncidentResponse get(@PathVariable Long id) {
        IncidentEntity incident = incidentQueryService.get(id);
        return IncidentResponse.from(incident,
                incidentQueryService.monitorName(incident.getMonitorId()));
    }

    private static IncidentStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return IncidentStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("status must be OPEN or RESOLVED");
        }
    }
}
