package dev.pulseops.heartbeat;

public class NotHeartbeatMonitorException extends RuntimeException {

    public NotHeartbeatMonitorException() {
        super("monitor is not a heartbeat monitor");
    }
}
