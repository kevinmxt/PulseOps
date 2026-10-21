package dev.pulseops.monitor.target;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@FunctionalInterface
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
