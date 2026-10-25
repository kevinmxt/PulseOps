package dev.pulseops.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pulseops.auth")
public record AuthProperties(
        @DefaultValue("false") boolean cookieSecure,
        @DefaultValue("") String bootstrapUsername,
        @DefaultValue("") String bootstrapPasswordHash) {
}
