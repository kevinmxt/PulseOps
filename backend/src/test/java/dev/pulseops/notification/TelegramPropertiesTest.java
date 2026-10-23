package dev.pulseops.notification;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramPropertiesTest {

    @Test
    void isDisabledWhenCredentialsAreMissing() {
        TelegramProperties properties = new TelegramProperties(null, null, null);

        assertThat(properties.isConfigured()).isFalse();
        assertThat(properties.apiBaseUrl()).isEqualTo("https://api.telegram.org");
    }

    @Test
    void isConfiguredWhenBothCredentialsArePresent() {
        TelegramProperties properties = new TelegramProperties(" token ", " chat ", null);

        assertThat(properties.isConfigured()).isTrue();
        assertThat(properties.botToken()).isEqualTo("token");
        assertThat(properties.chatId()).isEqualTo("chat");
    }

    @Test
    void rejectsHalfConfiguration() {
        assertThatThrownBy(() -> new TelegramProperties("token", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bot token");
        assertThatThrownBy(() -> new TelegramProperties(null, "chat", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chat id");
    }

    @Test
    void removesTrailingSlashFromBaseUrl() {
        TelegramProperties properties = new TelegramProperties(null, null, "http://127.0.0.1:8080/");

        assertThat(properties.apiBaseUrl()).isEqualTo("http://127.0.0.1:8080");
    }
}
