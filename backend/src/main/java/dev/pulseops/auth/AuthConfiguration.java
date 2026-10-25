package dev.pulseops.auth;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Map;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfiguration {

    static final int BCRYPT_COST = 12;

    @Bean
    PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder("bcrypt",
                Map.of("bcrypt", new BCryptPasswordEncoder(BCRYPT_COST)));
    }

    @Bean
    AuthStartupValidator authStartupValidator(AppUserRepository userRepository,
                                              AuthProperties properties,
                                              Environment environment,
                                              Clock clock) {
        return new AuthStartupValidator(userRepository, properties,
                environment.getProperty("server.address", ""), clock);
    }

    @Bean
    SmartInitializingSingleton authStartupCheck(AuthStartupValidator validator) {
        return validator::validateAndBootstrap;
    }
}
