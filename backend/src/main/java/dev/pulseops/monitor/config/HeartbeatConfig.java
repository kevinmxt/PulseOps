package dev.pulseops.monitor.config;

public record HeartbeatConfig(
        String name,
        int intervalSeconds,
        int graceSeconds,
        boolean enabled) {
}
