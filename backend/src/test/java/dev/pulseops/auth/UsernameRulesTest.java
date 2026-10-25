package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsernameRulesTest {

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(UsernameRules.normalize("  admin\t")).isEqualTo("admin");
        assertThat(UsernameRules.normalize("\nAdmin.User-1_2 ")).isEqualTo("Admin.User-1_2");
    }

    @Test
    void preservesCase() {
        assertThat(UsernameRules.normalize("Admin")).isEqualTo("Admin");
        assertThat(UsernameRules.normalize("admin")).isEqualTo("admin");
    }

    @Test
    void acceptsBoundaryLengths() {
        assertThat(UsernameRules.normalize("a".repeat(64))).hasSize(64);
        assertThat(UsernameRules.normalize("a")).isEqualTo("a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "bad name", "user@host", "üser", "a.b/c"})
    void rejectsInvalidFormat(String username) {
        assertThatThrownBy(() -> UsernameRules.normalize(username))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UsernameRules.isValid(username)).isFalse();
    }

    @Test
    void rejectsOverlongUsername() {
        String username = "a".repeat(65);

        assertThatThrownBy(() -> UsernameRules.normalize(username))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UsernameRules.isValid(username)).isFalse();
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> UsernameRules.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UsernameRules.isValid(null)).isFalse();
    }
}
