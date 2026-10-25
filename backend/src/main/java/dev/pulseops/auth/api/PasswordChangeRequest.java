package dev.pulseops.auth.api;

public record PasswordChangeRequest(String currentPassword, String newPassword) {
}
