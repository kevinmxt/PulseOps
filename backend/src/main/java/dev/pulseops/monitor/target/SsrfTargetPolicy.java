package dev.pulseops.monitor.target;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

public class SsrfTargetPolicy implements TargetPolicy {

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
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0) {
                return true;
            }
            return first == 100 && second >= 64 && second <= 127;
        }
        if (bytes.length == 16) {
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
