package dev.pulseops.auth;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AppUserRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final String HASH =
            "{bcrypt}$2a$12$abcdefghijklmnopqrstuuABCDEFGHIJKLMNOPQRSTUVWXYZ01234";

    @Autowired
    Flyway flyway;

    @Autowired
    AppUserRepository repository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void appliesV12MigrationAndPersistsUser() {
        assertThat(flyway.info().applied())
                .anySatisfy(info -> assertThat(info.getVersion().getVersion()).isEqualTo("12"));

        Instant now = Instant.parse("2026-10-25T08:00:00Z");
        AppUserEntity user = new AppUserEntity();
        user.setUsername("repository-admin");
        user.setPasswordHash(HASH);
        user.setRole(AppRole.ADMIN);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        AppUserEntity saved = repository.save(user);

        assertThat(saved.getId()).isNotNull();
        AppUserEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUsername()).isEqualTo("repository-admin");
        assertThat(reloaded.getPasswordHash()).isEqualTo(HASH);
        assertThat(reloaded.getRole()).isEqualTo(AppRole.ADMIN);
        assertThat(reloaded.getCreatedAt()).isEqualTo(now);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void findsUsernameCaseSensitively() {
        AppUserEntity user = user("CaseSensitiveUser");
        repository.save(user);

        assertThat(repository.findByUsername("CaseSensitiveUser")).isPresent();
        assertThat(repository.findByUsername("casesensitiveuser")).isEmpty();
    }

    @Test
    void trimsAndValidatesUsernameInputForLookup() {
        repository.save(user("TrimmedInputUser"));

        assertThat(repository.findByUsernameInput("  TrimmedInputUser  ")).isPresent();
        assertThat(repository.findByUsernameInput("trimmedinputuser")).isEmpty();
        assertThat(repository.findByUsernameInput("bad name")).isEmpty();
    }

    @Test
    void rejectsDuplicateUsername() {
        repository.save(user("duplicate-user"));

        assertThatThrownBy(() -> repository.saveAndFlush(user("duplicate-user")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsInvalidUsernameFormatAtDatabaseLevel() {
        assertThatThrownBy(() -> insertUser("bad name", "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedRoleAtDatabaseLevel() {
        assertThatThrownBy(() -> insertUser("viewer-user", "VIEWER"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private AppUserEntity user(String username) {
        Instant now = Instant.parse("2026-10-25T08:00:00Z");
        AppUserEntity user = new AppUserEntity();
        user.setUsername(username);
        user.setPasswordHash(HASH);
        user.setRole(AppRole.ADMIN);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    private void insertUser(String username, String role) {
        jdbcTemplate.update("""
                INSERT INTO app_user (username, password_hash, role, created_at, updated_at)
                VALUES (?, ?, ?, NOW(), NOW())
                """, username, HASH, role);
    }
}
