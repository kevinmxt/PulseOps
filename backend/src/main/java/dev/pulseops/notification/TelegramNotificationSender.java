package dev.pulseops.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class TelegramNotificationSender {

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int RESPONSE_TIMEOUT_MS = 10000;

    private final CloseableHttpClient httpClient;
    private final TelegramProperties properties;
    private final ObjectMapper objectMapper;

    public TelegramNotificationSender(@Qualifier("notificationHttpClient") CloseableHttpClient notificationHttpClient,
                                      TelegramProperties properties,
                                      ObjectMapper objectMapper) {
        this.httpClient = notificationHttpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void send(String text) {
        if (!properties.isConfigured()) {
            throw new NotificationSendException("Telegram channel is not configured", false);
        }

        String payload;
        try {
            payload = objectMapper.writeValueAsString(Map.of(
                    "chat_id", properties.chatId(),
                    "text", text));
        } catch (JsonProcessingException e) {
            throw new NotificationSendException("Failed to build Telegram request", false);
        }

        HttpPost request = new HttpPost(
                properties.apiBaseUrl() + "/bot" + properties.botToken() + "/sendMessage");
        request.setConfig(RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                .setConnectTimeout(Timeout.ofMilliseconds(CONNECT_TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(RESPONSE_TIMEOUT_MS))
                .build());
        request.setEntity(new StringEntity(payload,
                ContentType.create("application/json", StandardCharsets.UTF_8)));

        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                boolean retryable = status == 429 || status >= 500;
                throw new NotificationSendException("Telegram returned HTTP " + status, retryable);
            }
        } catch (IOException e) {
            throw new NotificationSendException(
                    "Telegram request failed: " + e.getClass().getSimpleName(), true, e);
        }
    }

    public String sanitize(String message) {
        if (message == null || properties.botToken() == null) {
            return message;
        }
        return message.replace(properties.botToken(), "***");
    }
}
