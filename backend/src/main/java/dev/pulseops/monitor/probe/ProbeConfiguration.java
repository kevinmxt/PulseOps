package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.target.HostResolver;
import dev.pulseops.monitor.target.SsrfDnsResolver;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProbeConfiguration {

    @Bean
    DnsResolver probeDnsResolver(HostResolver hostResolver) {
        return new SsrfDnsResolver(hostResolver);
    }

    @Bean(destroyMethod = "close")
    CloseableHttpClient probeHttpClient(DnsResolver dnsResolver) {
        PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(dnsResolver)
                .build();
        return HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }
}
