package dev.pulseops.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulseops.telegram")
public record TelegramProperties(String botToken, String chatId, String apiBaseUrl) {

    private static final String DEFAULT_API_BASE_URL = "https://api.telegram.org";

    public TelegramProperties {
        botToken = normalize(botToken);
        chatId = normalize(chatId);
        apiBaseUrl = normalizeBaseUrl(apiBaseUrl);
        if ((botToken == null) != (chatId == null)) {
            throw new IllegalStateException(
                    "Telegram notification requires both a bot token and a chat id");
        }
    }

    public boolean isConfigured() {
        return botToken != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.isBlank()
                ? DEFAULT_API_BASE_URL
                : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
