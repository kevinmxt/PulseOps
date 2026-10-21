package dev.pulseops.monitor;

public class MonitorDisabledException extends RuntimeException {

    public MonitorDisabledException(Long monitorId) {
        super("Monitor " + monitorId + " is disabled");
    }
}
