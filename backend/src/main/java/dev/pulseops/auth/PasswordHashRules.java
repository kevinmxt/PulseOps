package dev.pulseops.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PasswordHashRules {

    private static final Pattern BCRYPT =
            Pattern.compile("^\\{bcrypt\\}\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");
    private static final int REQUIRED_COST = 12;

    private PasswordHashRules() {
    }

    public static boolean isValidBcryptHash(String passwordHash) {
        if (passwordHash == null) {
            return false;
        }
        Matcher matcher = BCRYPT.matcher(passwordHash);
        if (!matcher.matches()) {
            return false;
        }
        return Integer.parseInt(matcher.group(1)) == REQUIRED_COST;
    }
}
