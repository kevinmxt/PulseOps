package dev.pulseops.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUserEntity, Long> {

    Optional<AppUserEntity> findByUsername(String username);

    default Optional<AppUserEntity> findByUsernameInput(String rawUsername) {
        if (!UsernameRules.isValid(rawUsername)) {
            return Optional.empty();
        }
        return findByUsername(UsernameRules.normalize(rawUsername));
    }
}
