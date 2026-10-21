package dev.pulseops.monitor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MonitorConfigValidatorTest {

    private final MonitorConfigValidator validator = new MonitorConfigValidator();

    @Test
    void appliesDefaultsForOmittedFields() {
        MonitorConfig config = validator.validate(new MonitorConfigInput(
                "My API",
                "https://example.com/health",
                null,
                null,
                null,
                null,
                null,
                null,
                null));

        assertThat(config.name()).isEqualTo("My API");
        assertThat(config.targetUrl().toString()).isEqualTo("https://example.com/health");
        assertThat(config.method()).isEqualTo(HttpMethod.GET);
        assertThat(config.intervalSeconds()).isEqualTo(60);
        assertThat(config.connectTimeoutMs()).isEqualTo(3000);
        assertThat(config.responseTimeoutMs()).isEqualTo(10000);
        assertThat(config.expectedStatusMin()).isEqualTo(200);
        assertThat(config.expectedStatusMax()).isEqualTo(299);
        assertThat(config.enabled()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com",
            "https://user:pass@example.com",
            "https://example.com/#fragment",
            "not a url",
            "https:///path"
    })
    void rejectsInvalidTargetUrls(String url) {
        assertInvalid("targetUrl", inputWithTarget(url));
    }

    @Test
    void rejectsOverlongNameAndUrl() {
        String longName = "x".repeat(101);
        assertInvalid("name", new MonitorConfigInput(
                longName, "https://example.com", null, null, null, null, null, null, null));

        String longUrl = "https://example.com/" + "a".repeat(2048);
        assertInvalid("targetUrl", inputWithTarget(longUrl));
    }

    @Test
    void rejectsUnsupportedMethod() {
        MonitorConfigInput input = new MonitorConfigInput(
                "API", "https://example.com", "POST", null, null, null, null, null, null);

        assertInvalid("method", input);
    }

    @Test
    void rejectsOutOfRangeInterval() {
        assertInvalid("intervalSeconds", inputWithInterval(9));
        assertInvalid("intervalSeconds", inputWithInterval(86401));
    }

    @Test
    void rejectsOutOfRangeTimeouts() {
        MonitorConfigInput connect = new MonitorConfigInput(
                "API", "https://example.com", null, null, 999, null, null, null, null);
        assertInvalid("connectTimeoutMs", connect);

        MonitorConfigInput response = new MonitorConfigInput(
                "API", "https://example.com", null, null, null, 30001, null, null, null);
        assertInvalid("responseTimeoutMs", response);
    }

    @Test
    void rejectsInvertedExpectedStatusRange() {
        MonitorConfigInput input = new MonitorConfigInput(
                "API", "https://example.com", null, null, null, null, 500, 400, null);

        assertInvalid("expectedStatusMin", input);
    }

    @Test
    void acceptsHeadMethodAndTrimsName() {
        MonitorConfig config = validator.validate(new MonitorConfigInput(
                "  My API  ", "https://example.com", "head", null, null, null, null, null, false));

        assertThat(config.name()).isEqualTo("My API");
        assertThat(config.method()).isEqualTo(HttpMethod.HEAD);
        assertThat(config.enabled()).isFalse();
    }

    private MonitorConfigInput inputWithTarget(String url) {
        return new MonitorConfigInput(
                "API", url, null, null, null, null, null, null, null);
    }

    private MonitorConfigInput inputWithInterval(int interval) {
        return new MonitorConfigInput(
                "API", "https://example.com", null, interval, null, null, null, null, null);
    }

    private void assertInvalid(String field, MonitorConfigInput input) {
        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOfSatisfying(InvalidMonitorConfigException.class,
                        e -> assertThat(e.field()).isEqualTo(field));
    }
}
