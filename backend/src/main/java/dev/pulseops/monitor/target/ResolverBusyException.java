package dev.pulseops.monitor.target;

import java.net.UnknownHostException;

public class ResolverBusyException extends UnknownHostException {

    public ResolverBusyException(String host) {
        super("resolver busy: " + host);
    }
}
