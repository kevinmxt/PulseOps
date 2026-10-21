package dev.pulseops.monitor.config;

import java.net.URI;

public record MonitorConfig(
        String name,
        URI targetUrl,
        HttpMethod method,
        int intervalSeconds,
        int connectTimeoutMs,
        int responseTimeoutMs,
        int expectedStatusMin,
        int expectedStatusMax,
        boolean enabled) {
}
