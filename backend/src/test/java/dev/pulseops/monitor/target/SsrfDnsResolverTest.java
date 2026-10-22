package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfDnsResolverTest {

    private final SsrfDnsResolver resolver = new SsrfDnsResolver(
            host -> List.of(InetAddress.getByName(host)));

    @Test
    void returnsResolvedPublicAddresses() throws Exception {
        InetAddress[] addresses = resolver.resolve("8.8.8.8");

        assertThat(addresses).hasSize(1);
        assertThat(addresses[0].getHostAddress()).isEqualTo("8.8.8.8");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "::1",
            "10.1.2.3",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.169.254",
            "fe80::1",
            "fc00::1",
            "100.64.0.1",
            "0.0.0.0",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "198.18.0.1",
            "240.0.0.1",
            "255.255.255.255",
            "192.0.0.1",
            "192.88.99.1",
            "2001:db8::1",
            "100::1",
            "3fff::1",
            "64:ff9b:1::1",
            "5f00::1",
            "2001:2::1",
            "2001:20::1",
            "2001:1::1",
            "2001::1",
            "2002:7f00:1::",
            "::ffff:10.0.0.1",
            "64:ff9b::10.0.0.1"
    })
    void refusesToResolveRestrictedAddressesAtExecutionTime(String ip) {
        assertThatThrownBy(() -> resolver.resolve(ip))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void resolvesPublicNat64Address() throws Exception {
        InetAddress[] addresses = resolver.resolve("64:ff9b::8.8.8.8");

        assertThat(addresses).hasSize(1);
    }

    @Test
    void rejectsResolutionWhenAnyAddressIsRestricted() {
        SsrfDnsResolver mixed = new SsrfDnsResolver(host -> List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")));

        assertThatThrownBy(() -> mixed.resolve("example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsEmptyResolution() {
        SsrfDnsResolver empty = new SsrfDnsResolver(host -> List.of());

        assertThatThrownBy(() -> empty.resolve("example.com"))
                .isInstanceOf(UnknownHostException.class);
    }
}
