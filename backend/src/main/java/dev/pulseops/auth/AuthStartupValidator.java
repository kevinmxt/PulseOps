package dev.pulseops.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AuthStartupValidator {

    private static final Logger log = LoggerFactory.getLogger(AuthStartupValidator.class);
    private static final Set<String> LOOPBACK_ADDRESSES =
            Set.of("127.0.0.1", "::1", "localhost");

    private final AppUserRepository userRepository;
    private final AuthProperties properties;
    private final String serverAddress;
    private final Clock clock;

    public AuthStartupValidator(AppUserRepository userRepository, AuthProperties properties,
                                String serverAddress, Clock clock) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.serverAddress = serverAddress;
        this.clock = clock;
    }

    public void validateAndBootstrap() {
        long existingUsers = userRepository.count();
        boolean usernameProvided = StringUtils.hasText(properties.bootstrapUsername());
        boolean hashProvided = StringUtils.hasText(properties.bootstrapPasswordHash());
        String bootstrapUsername = null;

        if (existingUsers == 0) {
            requireBootstrapCredentials(usernameProvided, hashProvided);
            bootstrapUsername = validateBootstrapUsername();
            validateBootstrapHash();
        }

        if (!isLoopback(serverAddress) && !properties.cookieSecure()) {
            String address = StringUtils.hasText(serverAddress) ? serverAddress.strip() : "<unset>";
            throw new AuthStartupException(
                    "server.address is " + address + " (not a loopback address) and "
                            + "pulseops.auth.cookie-secure is false. Bind to 127.0.0.1, ::1 or "
                            + "localhost for plain HTTP, or enable secure cookies behind TLS.");
        }

        if (existingUsers > 0) {
            if (usernameProvided || hashProvided) {
                log.warn("Bootstrap credentials are configured but app_user is not empty; "
                        + "ignoring them and keeping existing accounts unchanged.");
            }
            return;
        }

        createBootstrapAdmin(bootstrapUsername);
    }

    private void requireBootstrapCredentials(boolean usernameProvided, boolean hashProvided) {
        if (usernameProvided && hashProvided) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (!usernameProvided) {
            missing.add("PULSEOPS_AUTH_BOOTSTRAP_USERNAME");
        }
        if (!hashProvided) {
            missing.add("PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH");
        }
        throw new AuthStartupException("app_user is empty but bootstrap credentials are missing: "
                + String.join(", ", missing) + ". Set both variables for the first startup.");
    }

    private String validateBootstrapUsername() {
        try {
            return UsernameRules.normalize(properties.bootstrapUsername());
        } catch (IllegalArgumentException ex) {
            throw new AuthStartupException(
                    "PULSEOPS_AUTH_BOOTSTRAP_USERNAME is invalid: " + ex.getMessage());
        }
    }

    private void validateBootstrapHash() {
        if (!PasswordHashRules.isValidBcryptHash(properties.bootstrapPasswordHash())) {
            throw new AuthStartupException("PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH must be a valid "
                    + "{bcrypt}$2a$12$.. or {bcrypt}$2b$12$.. or {bcrypt}$2y$12$.. hash.");
        }
    }

    private void createBootstrapAdmin(String username) {
        Instant now = clock.instant();
        AppUserEntity user = new AppUserEntity();
        user.setUsername(username);
        user.setPasswordHash(properties.bootstrapPasswordHash());
        user.setRole(AppRole.ADMIN);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userRepository.save(user);
        log.info("Created bootstrap admin account because app_user was empty.");
    }

    private static boolean isLoopback(String address) {
        if (address == null) {
            return false;
        }
        return LOOPBACK_ADDRESSES.contains(address.strip().toLowerCase(Locale.ROOT));
    }
}
