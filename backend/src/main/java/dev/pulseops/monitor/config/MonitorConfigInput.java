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
        Boolean enabled,
        Integer failureThreshold,
        Integer recoveryThreshold,
        String type,
        Integer heartbeatGraceSeconds) {

    public MonitorConfigInput(String name, String targetUrl, String method, Integer intervalSeconds,
                              Integer connectTimeoutMs, Integer responseTimeoutMs, Integer expectedStatusMin,
                              Integer expectedStatusMax, Boolean enabled, Integer failureThreshold,
                              Integer recoveryThreshold) {
        this(name, targetUrl, method, intervalSeconds, connectTimeoutMs, responseTimeoutMs, expectedStatusMin,
                expectedStatusMax, enabled, failureThreshold, recoveryThreshold, null, null);
    }
}
