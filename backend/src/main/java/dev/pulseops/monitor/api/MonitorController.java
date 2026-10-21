package dev.pulseops.monitor.api;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorService;
import dev.pulseops.monitor.config.MonitorConfigInput;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/monitors")
public class MonitorController {

    private final MonitorService monitorService;

    public MonitorController(MonitorService monitorService) {
        this.monitorService = monitorService;
    }

    @PostMapping
    public ResponseEntity<MonitorResponse> create(@RequestBody MonitorConfigInput request) {
        MonitorEntity created = monitorService.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.getId())
                .toUri();
        return ResponseEntity.created(location).body(MonitorResponse.from(created));
    }

    @GetMapping
    public PageResponse<MonitorResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<MonitorEntity> result = monitorService.list(page, size);
        List<MonitorResponse> items = result.getContent().stream()
                .map(MonitorResponse::from)
                .toList();
        return new PageResponse<>(items, page, size, result.getTotalElements());
    }

    @GetMapping("/{id}")
    public MonitorResponse get(@PathVariable Long id) {
        return MonitorResponse.from(monitorService.get(id));
    }

    @PutMapping("/{id}")
    public MonitorResponse update(@PathVariable Long id, @RequestBody MonitorConfigInput request) {
        return MonitorResponse.from(monitorService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        monitorService.delete(id);
    }
}
