package dev.pulseops.monitor.target;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class TimeoutHostResolver implements HostResolver {

    private final HostResolver delegate;
    private final Duration timeout;

    public TimeoutHostResolver(HostResolver delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        CompletableFuture<List<InetAddress>> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(delegate.resolve(host));
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        });

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new UnknownHostException(host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException(host);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownHostException unknownHost) {
                throw unknownHost;
            }
            UnknownHostException failure = new UnknownHostException(host);
            failure.initCause(e.getCause());
            throw failure;
        }
    }
}
