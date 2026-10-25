package dev.pulseops.auth;

public class InvalidNewPasswordException extends RuntimeException {

    public InvalidNewPasswordException(String message) {
        super(message);
    }
}
