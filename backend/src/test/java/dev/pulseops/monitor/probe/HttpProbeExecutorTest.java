package dev.pulseops.monitor.probe;

import dev.pulseops.monitor.target.ResolverBusyException;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;

import static org.assertj.core.api.Assertions.assertThat;

class HttpProbeExecutorTest {

    @Test
    void mapsConnectTimeoutExceptionToConnectTimeout() {
        assertThat(HttpProbeExecutor.classifyFailure(new ConnectTimeoutException("connect timed out")))
                .isEqualTo(CheckFailureType.CONNECT_TIMEOUT);
    }

    @Test
    void mapsSocketTimeoutExceptionToResponseTimeout() {
        assertThat(HttpProbeExecutor.classifyFailure(new SocketTimeoutException("Read timed out")))
                .isEqualTo(CheckFailureType.RESPONSE_TIMEOUT);
    }

    @Test
    void mapsUnresolvedAddressToConnectionFailure() {
        assertThat(HttpProbeExecutor.classifyFailure(new UnresolvedAddressException()))
                .isEqualTo(CheckFailureType.CONNECTION_FAILED);
    }

    @Test
    void mapsIoExceptionToConnectionFailure() {
        assertThat(HttpProbeExecutor.classifyFailure(new IOException("Connection reset")))
                .isEqualTo(CheckFailureType.CONNECTION_FAILED);
    }

    @Test
    void mapsResolverBusyToDnsResolverExhausted() {
        assertThat(HttpProbeExecutor.classifyFailure(new ResolverBusyException("example.com")))
                .isEqualTo(CheckFailureType.DNS_RESOLVER_EXHAUSTED);
    }

    @Test
    void mapsWrappedResolverBusyToDnsResolverExhausted() {
        RuntimeException wrapped = new RuntimeException(new ResolverBusyException("example.com"));

        assertThat(HttpProbeExecutor.classifyFailure(wrapped))
                .isEqualTo(CheckFailureType.DNS_RESOLVER_EXHAUSTED);
    }

    @Test
    void mapsPlainUnknownHostToConnectionFailure() {
        assertThat(HttpProbeExecutor.classifyFailure(new UnknownHostException("blocked.example")))
                .isEqualTo(CheckFailureType.CONNECTION_FAILED);
    }

    @Test
    void mapsWrappedConnectTimeoutToConnectTimeout() {
        RuntimeException wrapped = new RuntimeException(new ConnectTimeoutException("connect timed out"));

        assertThat(HttpProbeExecutor.classifyFailure(wrapped))
                .isEqualTo(CheckFailureType.CONNECT_TIMEOUT);
    }

    @Test
    void mapsUnexpectedFailureToInternalError() {
        assertThat(HttpProbeExecutor.classifyFailure(new IllegalStateException("boom")))
                .isEqualTo(CheckFailureType.INTERNAL_ERROR);
    }

    @Test
    void truncatesErrorSummaryToFiveHundredCharacters() {
        String summary = HttpProbeExecutor.truncateSummary("x".repeat(600));

        assertThat(summary).hasSize(500);
    }

    @Test
    void keepsShortErrorSummaryUnchanged() {
        assertThat(HttpProbeExecutor.truncateSummary("short")).isEqualTo("short");
    }
}
