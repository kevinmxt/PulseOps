package dev.pulseops.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class AuthBootstrapIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    AppUserRepository repository;

    @Test
    void createsSingleAdminFromTestBootstrapCredentials() {
        List<AppUserEntity> users = repository.findAll();

        assertThat(users).hasSize(1);
        AppUserEntity admin = users.get(0);
        assertThat(admin.getId()).isNotNull();
        assertThat(admin.getUsername()).isEqualTo("test-admin");
        assertThat(admin.getRole()).isEqualTo(AppRole.ADMIN);
        assertThat(PasswordHashRules.isValidBcryptHash(admin.getPasswordHash())).isTrue();
        assertThat(admin.getCreatedAt()).isNotNull();
        assertThat(admin.getUpdatedAt()).isEqualTo(admin.getCreatedAt());
    }
}
