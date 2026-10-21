package dev.pulseops.monitor.probe;

public enum CheckFailureType {
    CONNECTION_FAILED,
    CONNECT_TIMEOUT,
    RESPONSE_TIMEOUT,
    UNEXPECTED_STATUS,
    INTERNAL_ERROR
}
