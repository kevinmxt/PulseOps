package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AuthPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void bindsSecureDefaults() {
        runner.run(context -> {
            AuthProperties properties = context.getBean(AuthProperties.class);

            assertThat(properties.cookieSecure()).isFalse();
            assertThat(properties.bootstrapUsername()).isEmpty();
            assertThat(properties.bootstrapPasswordHash()).isEmpty();
        });
    }

    @Test
    void bindsConfiguredValues() {
        runner.withPropertyValues(
                        "pulseops.auth.cookie-secure=true",
                        "pulseops.auth.bootstrap-username=admin",
                        "pulseops.auth.bootstrap-password-hash={bcrypt}$2a$12$abc")
                .run(context -> {
                    AuthProperties properties = context.getBean(AuthProperties.class);

                    assertThat(properties.cookieSecure()).isTrue();
                    assertThat(properties.bootstrapUsername()).isEqualTo("admin");
                    assertThat(properties.bootstrapPasswordHash()).isEqualTo("{bcrypt}$2a$12$abc");
                });
    }

    @Configuration
    @EnableConfigurationProperties(AuthProperties.class)
    static class TestConfiguration {
    }
}
