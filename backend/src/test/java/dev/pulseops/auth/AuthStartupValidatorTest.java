package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AuthStartupValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");
    private static final String VALID_HASH =
            "{bcrypt}" + new BCryptPasswordEncoder(12).encode("test-only-password");

    private final AppUserRepository repository = mock(AppUserRepository.class);

    @Test
    void createsAdminWhenTableIsEmpty(CapturedOutput output) {
        when(repository.count()).thenReturn(0L);

        validator("127.0.0.1", false, "  admin  ", VALID_HASH).validateAndBootstrap();

        ArgumentCaptor<AppUserEntity> captor = ArgumentCaptor.forClass(AppUserEntity.class);
        verify(repository).save(captor.capture());
        AppUserEntity saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("admin");
        assertThat(saved.getPasswordHash()).isEqualTo(VALID_HASH);
        assertThat(saved.getRole()).isEqualTo(AppRole.ADMIN);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        assertThat(output).contains("Created bootstrap admin account");
    }

    @Test
    void rejectsEmptyTableWithoutUsername() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> validator("127.0.0.1", false, "  ", VALID_HASH)
                .validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("PULSEOPS_AUTH_BOOTSTRAP_USERNAME");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsEmptyTableWithoutHash() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> validator("127.0.0.1", false, "admin", "")
                .validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidBootstrapUsername() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> validator("127.0.0.1", false, "bad name", VALID_HASH)
                .validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("PULSEOPS_AUTH_BOOTSTRAP_USERNAME");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidBootstrapHash() {
        when(repository.count()).thenReturn(0L);

        assertThatThrownBy(() -> validator("127.0.0.1", false, "admin", "plain-text")
                .validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNonLoopbackBindWithoutSecureCookie() {
        when(repository.count()).thenReturn(3L);

        assertThatThrownBy(() -> validator("0.0.0.0", false, "", "").validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("server.address")
                .hasMessageContaining("cookie-secure");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsUnsetServerAddressWithoutSecureCookie() {
        when(repository.count()).thenReturn(3L);

        assertThatThrownBy(() -> validator("  ", false, "", "").validateAndBootstrap())
                .isInstanceOf(AuthStartupException.class)
                .hasMessageContaining("server.address");
    }

    @Test
    void allowsNonLoopbackBindWithSecureCookie() {
        when(repository.count()).thenReturn(3L);

        assertThatCode(() -> validator("0.0.0.0", true, "", "").validateAndBootstrap())
                .doesNotThrowAnyException();
        verify(repository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "::1", "[::1]", "localhost", "LOCALHOST", " 127.0.0.1 "})
    void allowsLoopbackBindWithoutSecureCookie(String address) {
        when(repository.count()).thenReturn(3L);

        assertThatCode(() -> validator(address, false, "", "").validateAndBootstrap())
                .doesNotThrowAnyException();
    }

    @Test
    void ignoresBootstrapVariablesWhenUsersExist(CapturedOutput output) {
        when(repository.count()).thenReturn(1L);

        assertThatCode(() -> validator("127.0.0.1", false, "ignored-admin", "not-a-valid-hash")
                .validateAndBootstrap())
                .doesNotThrowAnyException();

        verify(repository, never()).save(any());
        assertThat(output)
                .contains("WARN")
                .contains("ignoring");
    }

    @Test
    void doesNothingWhenUsersExistAndNoBootstrapVariables(CapturedOutput output) {
        when(repository.count()).thenReturn(1L);

        validator("127.0.0.1", false, "", "").validateAndBootstrap();

        verify(repository, never()).save(any());
        assertThat(output).doesNotContain("Created bootstrap admin account");
    }

    private AuthStartupValidator validator(String serverAddress, boolean cookieSecure,
                                           String bootstrapUsername, String bootstrapPasswordHash) {
        return new AuthStartupValidator(repository,
                new AuthProperties(cookieSecure, bootstrapUsername, bootstrapPasswordHash),
                serverAddress,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
