package dev.pulseops.common;

public record ApiErrorResponse(String code, String message, String traceId) {
}
