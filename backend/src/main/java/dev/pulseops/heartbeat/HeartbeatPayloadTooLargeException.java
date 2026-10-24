package dev.pulseops.heartbeat;

public class HeartbeatPayloadTooLargeException extends RuntimeException {

    public HeartbeatPayloadTooLargeException() {
        super("request body exceeds the allowed size");
    }
}
