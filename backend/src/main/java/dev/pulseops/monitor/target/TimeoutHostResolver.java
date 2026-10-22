package dev.pulseops.monitor.target;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves hostnames on a virtual thread with a caller-side timeout.
 *
 * <p>By default at most 16 underlying resolutions may be in flight. A timed-out
 * call does not release its permit until the underlying lookup actually
 * returns: system DNS calls do not reliably honor interruption, so releasing
 * earlier would let stuck lookups accumulate without bound. When all permits
 * are held, new resolutions fail fast with {@link UnknownHostException}.
 */
public class TimeoutHostResolver implements HostResolver {

    private static final int DEFAULT_MAX_IN_FLIGHT_RESOLUTIONS = 16;

    private final HostResolver delegate;
    private final Duration timeout;
    private final Semaphore inFlightPermits;

    public TimeoutHostResolver(HostResolver delegate, Duration timeout) {
        this(delegate, timeout, DEFAULT_MAX_IN_FLIGHT_RESOLUTIONS);
    }

    TimeoutHostResolver(HostResolver delegate, Duration timeout, int maxInFlightResolutions) {
        if (maxInFlightResolutions < 1) {
            throw new IllegalArgumentException("maxInFlightResolutions must be positive");
        }
        this.delegate = delegate;
        this.timeout = timeout;
        this.inFlightPermits = new Semaphore(maxInFlightResolutions);
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        if (!inFlightPermits.tryAcquire()) {
            throw new UnknownHostException(host);
        }

        CompletableFuture<List<InetAddress>> future = new CompletableFuture<>();
        try {
            Thread.ofVirtual().start(() -> {
                List<InetAddress> addresses = null;
                Throwable failure = null;
                try {
                    addresses = delegate.resolve(host);
                } catch (Throwable thrown) {
                    failure = thrown;
                }
                inFlightPermits.release();
                if (failure == null) {
                    future.complete(addresses);
                } else {
                    future.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            inFlightPermits.release();
            UnknownHostException error = new UnknownHostException(host);
            error.initCause(failure);
            throw error;
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new UnknownHostException(host);
        } catch (InterruptedException e) {
            future.cancel(true);
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
