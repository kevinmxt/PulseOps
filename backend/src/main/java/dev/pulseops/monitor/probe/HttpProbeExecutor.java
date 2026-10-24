package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.CheckFailureType;
import dev.pulseops.monitor.MonitorEntity;
import dev.pulseops.monitor.target.ResolverBusyException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.channels.UnresolvedAddressException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public class HttpProbeExecutor {

    static final int ERROR_SUMMARY_MAX_LENGTH = 500;

    private final CloseableHttpClient httpClient;
    private final Clock clock;

    public HttpProbeExecutor(@Qualifier("probeHttpClient") CloseableHttpClient httpClient,
                             Clock clock) {
        this.httpClient = httpClient;
        this.clock = clock;
    }

    public ProbeOutcome execute(MonitorEntity monitor) {
        Instant startedAt = clock.instant();
        Integer statusCode = null;
        boolean success = false;
        CheckFailureType failureType = null;
        String errorSummary = null;

        try {
            URI target = validateTarget(monitor.getTargetUrl());
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(monitor.getConnectTimeoutMs()))
                    .setConnectTimeout(Timeout.ofMilliseconds(monitor.getConnectTimeoutMs()))
                    .setResponseTimeout(Timeout.ofMilliseconds(monitor.getResponseTimeoutMs()))
                    .setRedirectsEnabled(false)
                    .build();
            ClassicHttpRequest request = new BasicClassicHttpRequest(monitor.getMethod().name(), target);
            HttpClientContext context = HttpClientContext.create();
            context.setRequestConfig(requestConfig);

            CloseableHttpResponse response = httpClient.execute(request, context);
            try {
                statusCode = response.getCode();
                success = statusCode >= monitor.getExpectedStatusMin()
                        && statusCode <= monitor.getExpectedStatusMax();
                if (!success) {
                    failureType = CheckFailureType.UNEXPECTED_STATUS;
                    errorSummary = "Unexpected HTTP status code: " + statusCode;
                }
            } finally {
                response.close(CloseMode.IMMEDIATE);
            }
        } catch (Exception failure) {
            success = false;
            failureType = classifyFailure(failure);
            errorSummary = summaryFor(failureType, failure, monitor);
        }

        Instant finishedAt = clock.instant();
        long durationMs = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        return new ProbeOutcome(startedAt, finishedAt, durationMs, success, failureType,
                statusCode, truncateSummary(errorSummary));
    }

    static CheckFailureType classifyFailure(Throwable failure) {
        if (hasCause(failure, ConnectTimeoutException.class)) {
            return CheckFailureType.CONNECT_TIMEOUT;
        }
        if (hasCause(failure, ResolverBusyException.class)) {
            return CheckFailureType.DNS_RESOLVER_EXHAUSTED;
        }
        if (hasCause(failure, SocketTimeoutException.class)) {
            return CheckFailureType.RESPONSE_TIMEOUT;
        }
        if (hasCause(failure, UnresolvedAddressException.class)
                || hasCause(failure, IOException.class)) {
            return CheckFailureType.CONNECTION_FAILED;
        }
        return CheckFailureType.INTERNAL_ERROR;
    }

    static String truncateSummary(String summary) {
        if (summary == null || summary.length() <= ERROR_SUMMARY_MAX_LENGTH) {
            return summary;
        }
        return summary.substring(0, ERROR_SUMMARY_MAX_LENGTH);
    }

    private static String summaryFor(CheckFailureType failureType, Throwable failure, MonitorEntity monitor) {
        return switch (failureType) {
            case CONNECTION_FAILED -> "Connection failed: " + rootCauseName(failure);
            case DNS_RESOLVER_EXHAUSTED -> "DNS resolver overloaded: too many in-flight resolutions";
            case CONNECT_TIMEOUT -> "Connection timed out after " + monitor.getConnectTimeoutMs() + " ms";
            case RESPONSE_TIMEOUT -> "Response timed out after " + monitor.getResponseTimeoutMs() + " ms";
            case UNEXPECTED_STATUS -> "Unexpected HTTP status code";
            case INTERNAL_ERROR -> "Probe failed: " + rootCauseName(failure);
            case HEARTBEAT_TIMEOUT -> "Heartbeat timeout";
        };
    }

    private static String rootCauseName(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static URI validateTarget(String raw) {
        URI target = URI.create(raw);
        String scheme = target.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Unsupported target scheme");
        }
        if (target.getHost() == null || target.getHost().isBlank()) {
            throw new IllegalArgumentException("Target has no host");
        }
        if (target.getRawUserInfo() != null) {
            throw new IllegalArgumentException("Target must not include user info");
        }
        return target;
    }
}
