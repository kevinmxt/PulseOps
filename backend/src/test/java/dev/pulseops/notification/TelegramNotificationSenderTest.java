package dev.pulseops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramNotificationSenderTest {

    @Test
    void removesBotTokenFromErrorMessages() {
        TelegramNotificationSender sender = new TelegramNotificationSender(
                null, new TelegramProperties("secret-token", "chat", null), new ObjectMapper());

        String sanitized = sender.sanitize("request to /botsecret-token/sendMessage failed");

        assertThat(sanitized)
                .doesNotContain("secret-token")
                .contains("/bot***/sendMessage");
    }
}
