package dev.pulseops.heartbeat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pulseops.heartbeat")
public record HeartbeatProperties(@DefaultValue("false") boolean requireHttps) {
}
