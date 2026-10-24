package dev.pulseops.heartbeat;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(HeartbeatProperties.class)
public class HeartbeatConfiguration {
}
