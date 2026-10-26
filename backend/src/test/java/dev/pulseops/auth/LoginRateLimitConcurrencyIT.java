package dev.pulseops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class LoginRateLimitConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static final int CONCURRENT_ATTEMPTS = 20;

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void concurrentWrongPasswordLoginsCannotExceedTheQuota() throws Exception {
        String username = "concurrent-" + UUID.randomUUID();
        Csrf csrf = bootstrapCsrf();

        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_ATTEMPTS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < CONCURRENT_ATTEMPTS; attempt++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return login(username, "wrong-password-123", csrf);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            int unauthorized = 0;
            int rateLimited = 0;
            for (Future<HttpResponse<String>> result : results) {
                HttpResponse<String> response = result.get(60, TimeUnit.SECONDS);
                switch (response.statusCode()) {
                    case 401 -> {
                        unauthorized++;
                        assertThat(objectMapper.readTree(response.body()).get("code").asText())
                                .isEqualTo("INVALID_CREDENTIALS");
                    }
                    case 429 -> {
                        rateLimited++;
                        assertThat(retryAfterSeconds(response)).isPositive();
                        assertThat(objectMapper.readTree(response.body()).get("code").asText())
                                .isEqualTo("RATE_LIMITED");
                    }
                    default -> throw new AssertionError(
                            "unexpected login status: " + response.statusCode());
                }
            }
            assertThat(unauthorized).isEqualTo(LoginRateLimiter.MAX_ATTEMPTS);
            assertThat(rateLimited).isEqualTo(CONCURRENT_ATTEMPTS - LoginRateLimiter.MAX_ATTEMPTS);
        } finally {
            executor.shutdownNow();
        }

        HttpResponse<String> followUp = login(username, "wrong-password-123", csrf);
        assertThat(followUp.statusCode()).isEqualTo(429);
        assertThat(retryAfterSeconds(followUp)).isPositive();
    }

    private HttpResponse<String> login(String username, String password, Csrf csrf) throws Exception {
        HttpClient client = newClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrf.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(loginJson(username, password)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Csrf bootstrapCsrf() throws Exception {
        HttpClient client = newClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/session"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("authenticated").asBoolean()).isFalse();
        return new Csrf(body.get("csrfToken").asText());
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static long retryAfterSeconds(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse(null);
        assertThat(header).isNotNull();
        long seconds = Long.parseLong(header);
        assertThat(seconds).isPositive();
        return seconds;
    }

    private static String loginJson(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private record Csrf(String token) {
    }
}
