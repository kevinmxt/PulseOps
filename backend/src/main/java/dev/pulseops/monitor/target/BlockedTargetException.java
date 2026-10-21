package dev.pulseops.monitor.target;

public class BlockedTargetException extends RuntimeException {

    public BlockedTargetException(String message) {
        super(message);
    }
}
