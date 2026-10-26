package dev.pulseops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "pulseops.auth.rate-limit.max-keys=2")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class LoginRateLimitCapacityIT {

    private static final String WRONG_PASSWORD = "wrong-password-123";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void rejectsNewKeysAndKeepsExistingQuotaWhenCapacityIsFull(CapturedOutput output) throws Exception {
        String saturatedUser = "capacity-a-" + UUID.randomUUID();
        String secondUser = "capacity-b-" + UUID.randomUUID();
        String newUser = "capacity-c-" + UUID.randomUUID();
        Csrf csrf = bootstrapCsrf();

        for (int attempt = 0; attempt < LoginRateLimiter.MAX_ATTEMPTS; attempt++) {
            assertThat(login(saturatedUser, WRONG_PASSWORD, csrf).statusCode()).isEqualTo(401);
        }
        assertThat(login(secondUser, WRONG_PASSWORD, csrf).statusCode()).isEqualTo(401);

        HttpResponse<String> capacityRejected = login(newUser, WRONG_PASSWORD, csrf);
        assertThat(capacityRejected.statusCode()).isEqualTo(429);
        assertThat(retryAfterSeconds(capacityRejected))
                .isEqualTo(LoginRateLimiter.WINDOW.toSeconds());
        assertThat(code(capacityRejected)).isEqualTo("RATE_LIMITED");
        assertThat(capacityRejected.body()).doesNotContain("capacity");

        HttpResponse<String> quotaRejected = login(saturatedUser, WRONG_PASSWORD, csrf);
        assertThat(quotaRejected.statusCode()).isEqualTo(429);
        assertThat(retryAfterSeconds(quotaRejected)).isPositive();
        assertThat(code(quotaRejected)).isEqualTo("RATE_LIMITED");

        assertThat(output.getOut()).contains("rate limiter capacity exhausted");
        assertThat(output.getOut())
                .doesNotContain(saturatedUser, secondUser, newUser, WRONG_PASSWORD);
    }

    private long retryAfterSeconds(HttpResponse<String> response) {
        String header = response.headers().firstValue("Retry-After").orElse(null);
        assertThat(header).isNotNull();
        long seconds = Long.parseLong(header);
        assertThat(seconds).isPositive();
        return seconds;
    }

    private String code(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body()).get("code").asText();
    }

    private HttpResponse<String> login(String username, String password, Csrf csrf) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Cookie", "XSRF-TOKEN=" + csrf.token())
                .header("X-XSRF-TOKEN", csrf.token())
                .POST(HttpRequest.BodyPublishers.ofString(loginJson(username, password)))
                .build();
        return newClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private Csrf bootstrapCsrf() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/session"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<String> response = newClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        return new Csrf(body.get("csrfToken").asText());
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private static String loginJson(String username, String password) {
        return """
                {"username":"%s","password":"%s"}
                """.formatted(username, password);
    }

    private record Csrf(String token) {
    }
}
