package dev.pulseops.monitor.api;

public record ApiErrorResponse(String code, String message, String traceId) {
}
