package dev.pulseops.monitor.target;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class SsrfDnsResolver implements DnsResolver {

    private final HostResolver hostResolver;

    public SsrfDnsResolver(HostResolver hostResolver) {
        this.hostResolver = hostResolver;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        List<InetAddress> addresses = hostResolver.resolve(host);
        if (addresses.isEmpty()) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (SsrfTargetPolicy.isBlocked(address)) {
                throw new UnknownHostException(host);
            }
        }
        return addresses.toArray(InetAddress[]::new);
    }

    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
