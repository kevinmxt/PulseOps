package dev.pulseops.monitor.target;

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
    TargetPolicy targetPolicy() {
        HostResolver dnsResolver = host -> List.copyOf(Arrays.asList(InetAddress.getAllByName(host)));
        return new SsrfTargetPolicy(new TimeoutHostResolver(dnsResolver, RESOLUTION_TIMEOUT));
    }
}
