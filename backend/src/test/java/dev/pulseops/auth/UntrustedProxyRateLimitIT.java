package dev.pulseops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.tomcat.remoteip.internal-proxies="
                + ProxyRateLimitSupport.EDGE_PROXY_REGEX)
@Testcontainers
class UntrustedProxyRateLimitIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    ObjectMapper objectMapper;

    @LocalServerPort
    int port;

    private HttpClient client;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        client = ProxyRateLimitSupport.client();
        baseUrl = "http://127.0.0.1:" + port;
    }

    @Test
    void spoofedForwardedForDoesNotSplitTheRateLimitKey() throws Exception {
        ProxyRateLimitSupport.Csrf csrf =
                ProxyRateLimitSupport.bootstrapCsrf(client, baseUrl, objectMapper);
        String username = ProxyRateLimitSupport.uniqueUsername("untrusted");

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            String spoofed = "203.0.113." + (10 + attempt);
            assertThat(login(username, spoofed, csrf).statusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        HttpResponse<String> limited = login(username, "198.51.100.77", csrf);
        assertThat(limited.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(limited.body()).contains("\"code\":\"RATE_LIMITED\"");
        assertThat(Integer.parseInt(limited.headers().firstValue("retry-after").orElse("0")))
                .isPositive();
    }

    private HttpResponse<String> login(String username, String forwardedFor,
                                       ProxyRateLimitSupport.Csrf csrf) throws Exception {
        return ProxyRateLimitSupport.login(
                client, baseUrl, username, "wrong-password-123", forwardedFor, csrf);
    }
}
