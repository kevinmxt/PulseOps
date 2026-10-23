package dev.pulseops.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramNotificationSenderTest {

    @Test
    void marksNetworkErrorsAsRetryable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        TelegramProperties properties = new TelegramProperties(
                "secret-token", "chat", "http://127.0.0.1:" + closedPort);

        try (CloseableHttpClient client = HttpClients.createDefault()) {
            TelegramNotificationSender sender =
                    new TelegramNotificationSender(client, properties, new ObjectMapper());

            assertThatThrownBy(() -> sender.send("text"))
                    .isInstanceOfSatisfying(NotificationSendException.class, failure -> {
                        assertThat(failure.isRetryable()).isTrue();
                        assertThat(failure.getMessage()).doesNotContain("secret-token");
                    });
        }
    }

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
