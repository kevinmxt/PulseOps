package dev.pulseops.monitor.target;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Only allows globally routable public addresses.
 *
 * <p>Restricted prefixes are taken from the IANA IPv4 and IPv6 Special-Purpose
 * Address Registries (queried 2026-10-22). IPv6 is default-deny outside
 * {@code 2000::/3} (global unicast). IPv4-mapped and NAT64 addresses are
 * unwrapped and evaluated with the IPv4 rules. Deprecated transition forms
 * (IPv4-compatible IPv6, Teredo, 6to4) and some protocol anycast prefixes are
 * blocked conservatively.
 *
 * <p>These application-level checks are defense in depth against DNS rebinding.
 * They do not replace an outbound firewall or network egress policy.
 */
public class SsrfTargetPolicy implements TargetPolicy {

    private static final NetworkPrefix IPV6_GLOBAL_UNICAST = prefix("2000::", 3);

    private static final List<NetworkPrefix> RESTRICTED_IPV4_PREFIXES = List.of(
            prefix("0.0.0.0", 8),
            prefix("10.0.0.0", 8),
            prefix("100.64.0.0", 10),
            prefix("127.0.0.0", 8),
            prefix("169.254.0.0", 16),
            prefix("172.16.0.0", 12),
            prefix("192.0.0.0", 24),
            prefix("192.0.2.0", 24),
            prefix("192.88.99.0", 24),
            prefix("192.168.0.0", 16),
            prefix("198.18.0.0", 15),
            prefix("198.51.100.0", 24),
            prefix("203.0.113.0", 24),
            prefix("224.0.0.0", 4),
            prefix("240.0.0.0", 4));

    private static final List<NetworkPrefix> RESTRICTED_IPV6_PREFIXES = List.of(
            // Non-global prefixes inside 2000::/3. Prefixes outside the global
            // unicast range are rejected by IPV6_GLOBAL_UNICAST.
            // Teredo and 6to4 can encapsulate IPv4, so they are blocked
            // conservatively rather than only unwrapping their embedded address.
            prefix("2001::", 32),
            prefix("2002::", 16),
            prefix("2001:1::1", 128),
            prefix("2001:1::2", 128),
            prefix("2001:2::", 48),
            prefix("2001:10::", 28),
            prefix("2001:20::", 28),
            prefix("2001:30::", 28),
            prefix("2001:db8::", 32),
            prefix("3fff::", 20));

    private final HostResolver hostResolver;

    public SsrfTargetPolicy(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    @Override
    public void validate(URI target) {
        String host = target.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedTargetException("Target has no host");
        }

        List<InetAddress> addresses;
        try {
            addresses = hostResolver.resolve(stripBrackets(host));
        } catch (UnknownHostException e) {
            throw new TargetResolutionException(host, e);
        }

        if (addresses.isEmpty()) {
            throw new BlockedTargetException("Target host did not resolve to any address");
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                throw new BlockedTargetException("Target resolves to a restricted address: " + address.getHostAddress());
            }
        }
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    static boolean isBlocked(InetAddress address) {
        return isBlocked(address.getAddress());
    }

    static boolean isBlocked(byte[] address) {
        if (address.length == 4) {
            return isRestrictedIpv4(address);
        }
        if (address.length != 16) {
            return true;
        }

        if (isIpv4Mapped(address)) {
            return isRestrictedIpv4(lastFour(address));
        }
        if (isIpv4Compatible(address)) {
            // Deprecated ::/96 form; never a legitimate monitoring target.
            return true;
        }
        byte[] nat64 = nat64EmbeddedIpv4(address);
        if (nat64 != null) {
            return isRestrictedIpv4(nat64);
        }
        if (!IPV6_GLOBAL_UNICAST.contains(address)) {
            return true;
        }
        return RESTRICTED_IPV6_PREFIXES.stream().anyMatch(networkPrefix -> networkPrefix.contains(address));
    }

    private static boolean isRestrictedIpv4(byte[] address) {
        return RESTRICTED_IPV4_PREFIXES.stream().anyMatch(networkPrefix -> networkPrefix.contains(address));
    }

    private static boolean isIpv4Mapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
    }

    private static boolean isIpv4Compatible(byte[] address) {
        for (int i = 0; i < 12; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] nat64EmbeddedIpv4(byte[] address) {
        if (address[0] != 0 || address[1] != 0x64
                || (address[2] & 0xff) != 0xff || (address[3] & 0xff) != 0x9b) {
            return null;
        }
        for (int i = 4; i < 12; i++) {
            if (address[i] != 0) {
                return null;
            }
        }
        return lastFour(address);
    }

    private static byte[] lastFour(byte[] address) {
        return new byte[]{address[12], address[13], address[14], address[15]};
    }

    private static NetworkPrefix prefix(String network, int prefixLength) {
        try {
            return new NetworkPrefix(InetAddress.getByName(network).getAddress(), prefixLength);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid network prefix: " + network, e);
        }
    }

    private record NetworkPrefix(byte[] network, int prefixLength) {

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }
}
