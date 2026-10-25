package dev.pulseops.auth.api;

public record SessionResponse(boolean authenticated, String username, String role, String csrfToken) {
}
