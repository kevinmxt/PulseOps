package dev.pulseops.auth;

public class InvalidCurrentPasswordException extends RuntimeException {

    public InvalidCurrentPasswordException() {
        super("current password is incorrect");
    }
}
