package dev.pulseops.monitor.target;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

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
            "0.0.0.0"
    })
    void rejectsNonPublicTargets(String ip) {
        assertThatThrownBy(() -> policy.validate(uriFor(ip)))
                .isInstanceOf(BlockedTargetException.class);
    }

    @Test
    void allowsPublicTargets() {
        assertThatCode(() -> policy.validate(uriFor("8.8.8.8")))
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
    void signalsResolutionFailure() {
        SsrfTargetPolicy failing = new SsrfTargetPolicy(host -> {
            throw new UnknownHostException(host);
        });

        assertThatThrownBy(() -> failing.validate(URI.create("http://does-not-exist.invalid/health")))
                .isInstanceOf(TargetResolutionException.class);
    }

    private URI uriFor(String host) {
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return URI.create("http://" + authority + "/health");
    }
}
