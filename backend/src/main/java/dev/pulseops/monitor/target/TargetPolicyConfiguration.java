package dev.pulseops.monitor.target;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableConfigurationProperties(ResolverHealthProperties.class)
public class TargetPolicyConfiguration {

    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    TimeoutHostResolver hostResolver(MeterRegistry meterRegistry, Clock clock) {
        HostResolver systemResolver = host -> List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
        return new TimeoutHostResolver(systemResolver, RESOLUTION_TIMEOUT, clock, meterRegistry);
    }

    @Bean
    HealthIndicator resolverHealthIndicator(TimeoutHostResolver hostResolver,
                                            ResolverHealthProperties properties,
                                            Clock clock) {
        return new ResolverHealthIndicator(hostResolver, properties, clock);
    }

    @Bean
    TargetPolicy targetPolicy(HostResolver hostResolver) {
        return new SsrfTargetPolicy(hostResolver);
    }
}
