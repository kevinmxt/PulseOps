package dev.pulseops.monitor;

public class MonitorNotFoundException extends RuntimeException {

    public MonitorNotFoundException(Long id) {
        super("Monitor " + id + " was not found");
    }
}
