package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SsrfTargetPolicyTest {

    private final SsrfTargetPolicy policy = new SsrfTargetPolicy(host -> {
        try {
            return List.of(InetAddress.getByName(host));
        } catch (UnknownHostException e) {
            throw e;
        }
    });

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
    void rejectsNonPublicTargets(String ip) {
        assertThatThrownBy(() -> policy.validate(uriFor(ip)))
                .isInstanceOf(BlockedTargetException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "8.8.8.8",
            "1.1.1.1",
            "2606:4700:4700::1111",
            "2001:4860:4860::8888",
            "::ffff:8.8.8.8",
            "64:ff9b::8.8.8.8"
    })
    void allowsGloballyRoutableTargets(String ip) {
        assertThatCode(() -> policy.validate(uriFor(ip)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsRestricted() {
        SsrfTargetPolicy mixed = new SsrfTargetPolicy(host -> List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("10.0.0.1")));

        assertThatThrownBy(() -> mixed.validate(URI.create("http://example.com/health")))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void rejectsRestrictedIpv4EmbeddedInIpv4MappedIpv6Bytes() {
        assertThat(SsrfTargetPolicy.isBlocked(ipv4MappedBytes(10, 0, 0, 1))).isTrue();
    }

    @Test
    void allowsPublicIpv4EmbeddedInIpv4MappedIpv6Bytes() {
        assertThat(SsrfTargetPolicy.isBlocked(ipv4MappedBytes(8, 8, 8, 8))).isFalse();
    }

    @Test
    void rejectsDeprecatedIpv4CompatibleIpv6Bytes() {
        byte[] compatible = new byte[16];
        compatible[12] = 8;
        compatible[13] = 8;
        compatible[14] = 8;
        compatible[15] = 8;

        assertThat(SsrfTargetPolicy.isBlocked(compatible)).isTrue();
    }

    @Test
    void rejectsWhenAnyResolvedAddressIsSpecialPurpose() {
        SsrfTargetPolicy mixed = new SsrfTargetPolicy(host -> List.of(
                InetAddress.getByName("8.8.8.8"),
                InetAddress.getByName("2001:db8::1")));

        assertThatThrownBy(() -> mixed.validate(URI.create("http://example.com/health")))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void signalsResolutionFailure() {
        SsrfTargetPolicy failing = new SsrfTargetPolicy(host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> failing.validate(URI.create("http://does-not-exist.invalid/health")))
                .isInstanceOf(TargetResolutionException.class);
    }

    private static byte[] ipv4MappedBytes(int... ipv4) {
        byte[] address = new byte[16];
        address[10] = (byte) 0xff;
        address[11] = (byte) 0xff;
        for (int i = 0; i < ipv4.length; i++) {
            address[12 + i] = (byte) ipv4[i];
        }
        return address;
    }

    private URI uriFor(String host) {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return URI.create("http://" + authority + "/health");
    }
}
