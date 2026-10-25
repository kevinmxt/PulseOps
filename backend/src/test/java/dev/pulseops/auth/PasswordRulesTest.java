package dev.pulseops.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordRulesTest {

    @Test
    void acceptsTwelveTo128Characters() {
        assertThat(PasswordRules.isValid("a".repeat(12))).isTrue();
        assertThat(PasswordRules.isValid("a".repeat(128))).isTrue();
    }

    @Test
    void rejectsShortLongAndMissingPasswords() {
        assertThat(PasswordRules.isValid("a".repeat(11))).isFalse();
        assertThat(PasswordRules.isValid("a".repeat(129))).isFalse();
        assertThat(PasswordRules.isValid(null)).isFalse();
        assertThat(PasswordRules.isValid("")).isFalse();
    }

    @Test
    void doesNotTrimWhitespace() {
        assertThat(PasswordRules.isValid(" " + "a".repeat(10) + " ")).isTrue();
        assertThat(PasswordRules.isValid(" " + "a".repeat(11) + " ")).isTrue();
    }
}
