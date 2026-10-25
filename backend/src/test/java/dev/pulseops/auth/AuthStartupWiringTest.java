package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthStartupWiringTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-25T08:00:00Z"), ZoneOffset.UTC);

    @Test
    void rejectsContextStartupWhenBootstrapCredentialsAreMissing() {
        ApplicationContextRunner runner = runner(mock(AppUserRepository.class));

        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isInstanceOf(AuthStartupException.class)
                    .hasMessage("app_user is empty but bootstrap credentials are missing: "
                            + "PULSEOPS_AUTH_BOOTSTRAP_USERNAME, "
                            + "PULSEOPS_AUTH_BOOTSTRAP_PASSWORD_HASH. "
                            + "Set both variables for the first startup.");
        });
    }

    @Test
    void createsBootstrapAdminThroughStartupHook() {
        AppUserRepository repository = mock(AppUserRepository.class);
        when(repository.count()).thenReturn(0L);
        ApplicationContextRunner runner = runner(repository)
                .withPropertyValues(
                        "server.address=127.0.0.1",
                        "pulseops.auth.bootstrap-username=admin",
                        "pulseops.auth.bootstrap-password-hash="
                                + "{bcrypt}$2a$12$VswLv87wCxJPl24RAvnFEObuemGowCtSVapPwkPotcd85N/.3CcZ2");

        runner.run(context -> {
            assertThat(context).hasNotFailed();
            verify(repository).save(any(AppUserEntity.class));
        });
    }

    private ApplicationContextRunner runner(AppUserRepository repository) {
        return new ApplicationContextRunner()
                .withUserConfiguration(AuthConfiguration.class)
                .withBean(Clock.class, () -> CLOCK)
                .withBean(AppUserRepository.class, () -> repository);
    }
}
