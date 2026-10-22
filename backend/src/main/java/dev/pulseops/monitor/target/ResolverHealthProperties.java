package dev.pulseops.monitor.target;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "pulseops.health.resolver")
public record ResolverHealthProperties(@DefaultValue("60s") Duration downAfter) {

    public ResolverHealthProperties {
        if (downAfter == null || downAfter.isNegative() || downAfter.isZero()) {
            throw new IllegalArgumentException("pulseops.health.resolver.down-after must be positive");
        }
    }
}
