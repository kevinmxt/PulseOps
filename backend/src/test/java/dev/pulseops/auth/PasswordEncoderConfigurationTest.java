package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordEncoderConfigurationTest {

    private final PasswordEncoder encoder = new AuthConfiguration().passwordEncoder();

    @Test
    void encodesWithBcryptCost12AndDelegatingPrefix() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(hash).startsWith("{bcrypt}$2a$12$");
        assertThat(PasswordHashRules.isValidBcryptHash(hash)).isTrue();
        assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
        assertThat(encoder.matches("wrong password", hash)).isFalse();
        assertThat(hash).doesNotContain("correct horse");
    }
}
