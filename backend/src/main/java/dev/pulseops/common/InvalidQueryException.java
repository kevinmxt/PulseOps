package dev.pulseops.common;

public class InvalidQueryException extends RuntimeException {

    public InvalidQueryException(String message) {
        super(message);
    }
}
