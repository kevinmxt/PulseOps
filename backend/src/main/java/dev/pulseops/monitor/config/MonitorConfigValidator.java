package dev.pulseops.monitor.config;

import dev.pulseops.monitor.MonitorType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;

@Component
public class MonitorConfigValidator {

    static final int NAME_MAX_LENGTH = 100;
    static final int URL_MAX_LENGTH = 2048;
    static final int INTERVAL_MIN_SECONDS = 10;
    static final int INTERVAL_MAX_SECONDS = 86400;
    static final int HEARTBEAT_INTERVAL_MIN_SECONDS = 30;
    static final int HEARTBEAT_INTERVAL_MAX_SECONDS = 2592000;
    static final int HEARTBEAT_GRACE_MIN_SECONDS = 0;
    static final int HEARTBEAT_GRACE_MAX_SECONDS = 86400;
    static final int CONNECT_TIMEOUT_MIN_MS = 1000;
    static final int CONNECT_TIMEOUT_MAX_MS = 10000;
    static final int RESPONSE_TIMEOUT_MIN_MS = 1000;
    static final int RESPONSE_TIMEOUT_MAX_MS = 30000;
    static final int STATUS_MIN = 100;
    static final int STATUS_MAX = 599;
    static final int THRESHOLD_MIN = 1;
    static final int THRESHOLD_MAX = 10;
    static final int DEFAULT_FAILURE_THRESHOLD = 3;
    static final int DEFAULT_RECOVERY_THRESHOLD = 2;
    static final int DEFAULT_INTERVAL_SECONDS = 60;
    static final int DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 300;
    static final int DEFAULT_HEARTBEAT_GRACE_SECONDS = 60;
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 3000;
    static final int DEFAULT_RESPONSE_TIMEOUT_MS = 10000;
    static final int DEFAULT_EXPECTED_STATUS_MIN = 200;
    static final int DEFAULT_EXPECTED_STATUS_MAX = 299;

    public MonitorType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            return MonitorType.HTTP;
        }
        try {
            return MonitorType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InvalidMonitorConfigException.validation("type", "must be HTTP or HEARTBEAT");
        }
    }

    public HeartbeatConfig validateHeartbeat(MonitorConfigInput input) {
        String name = validateName(input.name());
        int interval = valueOrDefault(input.intervalSeconds(), DEFAULT_HEARTBEAT_INTERVAL_SECONDS);
        requireRange("intervalSeconds", interval, HEARTBEAT_INTERVAL_MIN_SECONDS, HEARTBEAT_INTERVAL_MAX_SECONDS);
        int grace = valueOrDefault(input.heartbeatGraceSeconds(), DEFAULT_HEARTBEAT_GRACE_SECONDS);
        requireRange("heartbeatGraceSeconds", grace, HEARTBEAT_GRACE_MIN_SECONDS, HEARTBEAT_GRACE_MAX_SECONDS);
        boolean enabled = input.enabled() == null || input.enabled();

        return new HeartbeatConfig(name, interval, grace, enabled);
    }

    public MonitorConfig validate(MonitorConfigInput input) {
        String name = validateName(input.name());
        URI targetUrl = validateTargetUrl(input.targetUrl());
        HttpMethod method = validateMethod(input.method());
        int interval = valueOrDefault(input.intervalSeconds(), DEFAULT_INTERVAL_SECONDS);
        requireRange("intervalSeconds", interval, INTERVAL_MIN_SECONDS, INTERVAL_MAX_SECONDS);
        int connectTimeout = valueOrDefault(input.connectTimeoutMs(), DEFAULT_CONNECT_TIMEOUT_MS);
        requireRange("connectTimeoutMs", connectTimeout, CONNECT_TIMEOUT_MIN_MS, CONNECT_TIMEOUT_MAX_MS);
        int responseTimeout = valueOrDefault(input.responseTimeoutMs(), DEFAULT_RESPONSE_TIMEOUT_MS);
        requireRange("responseTimeoutMs", responseTimeout, RESPONSE_TIMEOUT_MIN_MS, RESPONSE_TIMEOUT_MAX_MS);
        int expectedMin = valueOrDefault(input.expectedStatusMin(), DEFAULT_EXPECTED_STATUS_MIN);
        int expectedMax = valueOrDefault(input.expectedStatusMax(), DEFAULT_EXPECTED_STATUS_MAX);
        requireRange("expectedStatusMin", expectedMin, STATUS_MIN, STATUS_MAX);
        requireRange("expectedStatusMax", expectedMax, STATUS_MIN, STATUS_MAX);
        if (expectedMin > expectedMax) {
            throw InvalidMonitorConfigException.validation("expectedStatusMin",
                    "must not be greater than expectedStatusMax");
        }
        int failureThreshold = valueOrDefault(input.failureThreshold(), DEFAULT_FAILURE_THRESHOLD);
        requireRange("failureThreshold", failureThreshold, THRESHOLD_MIN, THRESHOLD_MAX);
        int recoveryThreshold = valueOrDefault(input.recoveryThreshold(), DEFAULT_RECOVERY_THRESHOLD);
        requireRange("recoveryThreshold", recoveryThreshold, THRESHOLD_MIN, THRESHOLD_MAX);
        boolean enabled = input.enabled() == null || input.enabled();

        return new MonitorConfig(name, targetUrl, method, interval, connectTimeout,
                responseTimeout, expectedMin, expectedMax, enabled,
                failureThreshold, recoveryThreshold);
    }

    private String validateName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw InvalidMonitorConfigException.validation("name", "must not be blank");
        }
        String name = raw.trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw InvalidMonitorConfigException.validation("name",
                    "must be at most " + NAME_MAX_LENGTH + " characters");
        }
        return name;
    }

    private URI validateTargetUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw InvalidMonitorConfigException.invalidTarget("must not be blank");
        }
        if (raw.length() > URL_MAX_LENGTH) {
            throw InvalidMonitorConfigException.invalidTarget(
                    "must be at most " + URL_MAX_LENGTH + " characters");
        }

        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw InvalidMonitorConfigException.invalidTarget("must be a valid URL");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw InvalidMonitorConfigException.invalidTarget("must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw InvalidMonitorConfigException.invalidTarget("must include a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw InvalidMonitorConfigException.invalidTarget("must not include user info");
        }
        if (uri.getRawFragment() != null) {
            throw InvalidMonitorConfigException.invalidTarget("must not include a fragment");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw InvalidMonitorConfigException.invalidTarget("must use a valid port");
        }
        return uri;
    }

    private HttpMethod validateMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return HttpMethod.GET;
        }
        try {
            return HttpMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw InvalidMonitorConfigException.validation("method", "must be GET or HEAD");
        }
    }

    private static int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static void requireRange(String field, int value, int min, int max) {
        if (value < min || value > max) {
            throw InvalidMonitorConfigException.validation(field, "must be between " + min + " and " + max);
        }
    }
}
