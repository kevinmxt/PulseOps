package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHashRulesTest {

    @Test
    void acceptsGeneratedBcryptHash() {
        String hash = "{bcrypt}" + new BCryptPasswordEncoder(12).encode("test-password");

        assertThat(PasswordHashRules.isValidBcryptHash(hash)).isTrue();
    }

    @Test
    void acceptsBcryptVariantsAndOtherValidCosts() {
        String hash = new BCryptPasswordEncoder(12).encode("test-password");
        String twoY = hash.replace("$2a$", "$2y$");
        String twoB = hash.replace("$2a$", "$2b$");
        String cost10 = new BCryptPasswordEncoder(10).encode("test-password");

        assertThat(PasswordHashRules.isValidBcryptHash("{bcrypt}" + twoY)).isTrue();
        assertThat(PasswordHashRules.isValidBcryptHash("{bcrypt}" + twoB)).isTrue();
        assertThat(PasswordHashRules.isValidBcryptHash("{bcrypt}" + cost10)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "plain-text-password",
            "$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234",
            "{bcrypt}$2a$12$too-short",
            "{bcrypt}$2c$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234"
    })
    void rejectsMalformedHash(String hash) {
        assertThat(PasswordHashRules.isValidBcryptHash(hash)).isFalse();
    }

    @Test
    void rejectsNullAndOutOfRangeCosts() {
        assertThat(PasswordHashRules.isValidBcryptHash(null)).isFalse();
        assertThat(PasswordHashRules.isValidBcryptHash("{bcrypt}$2a$03$" + "A".repeat(53))).isFalse();
        assertThat(PasswordHashRules.isValidBcryptHash("{bcrypt}$2a$32$" + "A".repeat(53))).isFalse();
    }
}
