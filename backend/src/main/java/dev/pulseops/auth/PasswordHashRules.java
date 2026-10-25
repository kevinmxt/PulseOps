package dev.pulseops.auth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PasswordHashRules {

    private static final Pattern BCRYPT =
            Pattern.compile("^\\{bcrypt\\}\\$2[aby]\\$(\\d{2})\\$[./A-Za-z0-9]{53}$");
    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;

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
        int cost = Integer.parseInt(matcher.group(1));
        return cost >= MIN_COST && cost <= MAX_COST;
    }
}
