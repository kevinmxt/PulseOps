package dev.pulseops.monitor.target;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
public class TargetPolicyConfiguration {

    private static final Duration RESOLUTION_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    HostResolver hostResolver(MeterRegistry meterRegistry) {
        HostResolver systemResolver = host -> List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
        return new TimeoutHostResolver(systemResolver, RESOLUTION_TIMEOUT, meterRegistry);
    }

    @Bean
    TargetPolicy targetPolicy(HostResolver hostResolver) {
        return new SsrfTargetPolicy(hostResolver);
    }
}
