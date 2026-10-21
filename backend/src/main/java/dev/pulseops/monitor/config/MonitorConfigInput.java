package dev.pulseops.monitor.config;

public record MonitorConfigInput(
        String name,
        String targetUrl,
        String method,
        Integer intervalSeconds,
        Integer connectTimeoutMs,
        Integer responseTimeoutMs,
        Integer expectedStatusMin,
        Integer expectedStatusMax,
        Boolean enabled) {
}
