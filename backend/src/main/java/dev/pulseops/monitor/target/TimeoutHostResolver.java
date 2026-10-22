package dev.pulseops.monitor.target;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Resolves hostnames on a virtual thread with a caller-side timeout.
 *
 * <p>By default at most 16 underlying resolutions may be in flight. A timed-out
 * call does not release its permit until the underlying lookup actually
 * returns: system DNS calls do not reliably honor interruption, so releasing
 * earlier would let stuck lookups accumulate without bound. When all permits
 * are held, new resolutions fail fast with {@link UnknownHostException}.
 *
 * <p>Timeouts increment {@code resolver.timeout}; rejections increment
 * {@code resolver.exhausted}; the {@code resolver.permits.available} gauge
 * reports remaining capacity. Exhaustion logs once on entry and once when
 * permits become available again.
 */
public class TimeoutHostResolver implements HostResolver {

    private static final int DEFAULT_MAX_IN_FLIGHT_RESOLUTIONS = 16;
    private static final Logger log = LoggerFactory.getLogger(TimeoutHostResolver.class);

    private final HostResolver delegate;
    private final Duration timeout;
    private final Semaphore inFlightPermits;
    private final Counter timeoutCounter;
    private final Counter exhaustedCounter;
    private final AtomicBoolean exhausted = new AtomicBoolean();

    public TimeoutHostResolver(HostResolver delegate, Duration timeout, MeterRegistry meterRegistry) {
        this(delegate, timeout, DEFAULT_MAX_IN_FLIGHT_RESOLUTIONS, meterRegistry);
    }

    TimeoutHostResolver(HostResolver delegate, Duration timeout, int maxInFlightResolutions,
                        MeterRegistry meterRegistry) {
        if (maxInFlightResolutions < 1) {
            throw new IllegalArgumentException("maxInFlightResolutions must be positive");
        }
        this.delegate = delegate;
        this.timeout = timeout;
        this.inFlightPermits = new Semaphore(maxInFlightResolutions);
        Gauge.builder("resolver.permits.available", inFlightPermits, Semaphore::availablePermits)
                .description("DNS resolution permits not held by an in-flight lookup")
                .register(meterRegistry);
        this.timeoutCounter = meterRegistry.counter("resolver.timeout");
        this.exhaustedCounter = meterRegistry.counter("resolver.exhausted");
    }

    @Override
    public List<InetAddress> resolve(String host) throws UnknownHostException {
        if (!inFlightPermits.tryAcquire()) {
            exhaustedCounter.increment();
            if (exhausted.compareAndSet(false, true)) {
                log.warn("DNS resolver permits exhausted; rejecting resolution of {}", sanitize(host));
            }
            throw new ResolverBusyException(host);
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
                releasePermit();
                if (failure == null) {
                    future.complete(addresses);
                } else {
                    future.completeExceptionally(failure);
                }
            });
        } catch (Throwable failure) {
            releasePermit();
            UnknownHostException error = new UnknownHostException(host);
            error.initCause(failure);
            throw error;
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            timeoutCounter.increment();
            future.cancel(true);
            log.debug("DNS resolution of {} timed out after {} ms; permit remains held until the lookup returns",
                    sanitize(host), timeout.toMillis());
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

    private void releasePermit() {
        inFlightPermits.release();
        if (exhausted.get()
                && inFlightPermits.availablePermits() > 0
                && exhausted.compareAndSet(true, false)) {
            log.info("DNS resolver permits available again");
        }
    }

    private static String sanitize(String host) {
        if (host == null) {
            return "";
        }
        return host.replace('\r', '_').replace('\n', '_');
    }
}
