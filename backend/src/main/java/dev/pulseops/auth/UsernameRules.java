package dev.pulseops.auth;

import java.util.regex.Pattern;

public final class UsernameRules {

    static final int MAX_LENGTH = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9._-]+");

    private UsernameRules() {
    }

    public static String normalize(String rawUsername) {
        if (rawUsername == null) {
            throw new IllegalArgumentException("username must not be null");
        }
        String username = rawUsername.strip();
        if (username.isEmpty() || username.length() > MAX_LENGTH || !ALLOWED.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "username must be 1-64 characters matching [A-Za-z0-9._-]");
        }
        return username;
    }

    public static boolean isValid(String rawUsername) {
        if (rawUsername == null) {
            return false;
        }
        String username = rawUsername.strip();
        return !username.isEmpty() && username.length() <= MAX_LENGTH && ALLOWED.matcher(username).matches();
    }
}
