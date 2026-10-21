package dev.pulseops.monitor.target;

public class TargetResolutionException extends RuntimeException {

    public TargetResolutionException(String host, Throwable cause) {
        super("Failed to resolve target host: " + host, cause);
    }
}
