package dev.pulseops.monitor;

public class UnsupportedMonitorTypeException extends RuntimeException {

    public UnsupportedMonitorTypeException(Long monitorId, MonitorType type) {
        super("monitor " + monitorId + " has unsupported type " + type);
    }
}
