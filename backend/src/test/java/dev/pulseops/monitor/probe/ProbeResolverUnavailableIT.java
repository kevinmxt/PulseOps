package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.MonitorRepository;
import dev.pulseops.monitor.MonitorStatus;
import dev.pulseops.monitor.MonitorType;
import dev.pulseops.monitor.config.HttpMethod;
import dev.pulseops.monitor.target.ResolverBusyException;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@Import(ProbeResolverUnavailableIT.BusyDnsConfiguration.class)
class ProbeResolverUnavailableIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    ProbeService probeService;

    @Autowired
    MonitorRepository monitorRepository;

    @Autowired
    CheckResultRepository checkResultRepository;

    @TestConfiguration(proxyBeanMethods = false)
    static class BusyDnsConfiguration {

        @Bean
        @Primary
        DnsResolver busyDnsResolver() {
            return new DnsResolver() {
                @Override
                public InetAddress[] resolve(String host) throws UnknownHostException {
                    throw new ResolverBusyException(host);
                }

                @Override
                public String resolveCanonicalHostname(String host) {
                    return host;
                }
            };
        }
    }

    @BeforeEach
    void cleanDatabase() {
        checkResultRepository.deleteAll();
        monitorRepository.deleteAll();
    }

    @Test
    void recordsDnsResolverExhaustedAndMarksMonitorDown() {
        MonitorEntity monitor = saveMonitor();

        probeService.probe(monitor.getId());

        CheckResultEntity result = checkResultRepository.findAll().get(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureType()).isEqualTo(CheckFailureType.DNS_RESOLVER_EXHAUSTED);
        assertThat(result.getErrorSummary())
                .isEqualTo("DNS resolver overloaded: too many in-flight resolutions");
        assertThat(result.getHttpStatusCode()).isNull();

        MonitorEntity updated = monitorRepository.findById(monitor.getId()).orElseThrow();
        assertThat(updated.getCurrentStatus()).isEqualTo(MonitorStatus.DOWN);
        assertThat(updated.getLastSuccessAt()).isNull();
    }

    private MonitorEntity saveMonitor() {
        Instant now = Instant.parse("2026-10-22T08:00:00Z");
        MonitorEntity monitor = new MonitorEntity();
        monitor.setName("resolver unavailable test");
        monitor.setType(MonitorType.HTTP);
        monitor.setTargetUrl("http://unresolvable.test/health");
        monitor.setMethod(HttpMethod.GET);
        monitor.setIntervalSeconds(60);
        monitor.setConnectTimeoutMs(1000);
        monitor.setResponseTimeoutMs(2000);
        monitor.setExpectedStatusMin(200);
        monitor.setExpectedStatusMax(299);
        monitor.setEnabled(true);
        monitor.setCurrentStatus(MonitorStatus.UNKNOWN);
        monitor.setNextCheckAt(now);
        monitor.setCreatedAt(now);
        monitor.setUpdatedAt(now);
        return monitorRepository.save(monitor);
    }
}
