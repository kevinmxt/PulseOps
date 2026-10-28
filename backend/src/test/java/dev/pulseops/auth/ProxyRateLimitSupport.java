package dev.pulseops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

final class ProxyRateLimitSupport {

    static final String EDGE_PROXY_REGEX = "172\\.28\\.0\\.\\d{1,3}";

    private ProxyRateLimitSupport() {
    }

    static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    static Csrf bootstrapCsrf(HttpClient client, String baseUrl, ObjectMapper objectMapper)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/auth/session"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("session bootstrap failed: " + response.statusCode());
        }
        String setCookie = response.headers().allValues("set-cookie").stream()
                .filter(value -> value.startsWith("XSRF-TOKEN="))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "session bootstrap did not set XSRF-TOKEN"));
        String cookie = setCookie.split(";", 2)[0];
        String token = objectMapper.readTree(response.body()).get("csrfToken").asText();
        return new Csrf(cookie, token);
    }

    static HttpResponse<String> login(HttpClient client, String baseUrl, String username,
                                      String password, String forwardedFor, Csrf csrf)
            throws Exception {
        String body = "{\"username\":\"%s\",\"password\":\"%s\"}"
                .formatted(username, password);
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Cookie", csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token())
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    static String uniqueUsername(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    record Csrf(String cookie, String token) {
    }
}
