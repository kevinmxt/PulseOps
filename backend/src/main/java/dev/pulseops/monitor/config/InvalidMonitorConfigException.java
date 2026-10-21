package dev.pulseops.monitor.config;

public class InvalidMonitorConfigException extends RuntimeException {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INVALID_TARGET_URL = "INVALID_TARGET_URL";

    private final String code;
    private final String field;

    private InvalidMonitorConfigException(String code, String field, String reason) {
        super(field + ": " + reason);
        this.code = code;
        this.field = field;
    }

    public static InvalidMonitorConfigException validation(String field, String reason) {
        return new InvalidMonitorConfigException(VALIDATION_ERROR, field, reason);
    }

    public static InvalidMonitorConfigException invalidTarget(String reason) {
        return new InvalidMonitorConfigException(INVALID_TARGET_URL, "targetUrl", reason);
    }

    public String code() {
        return code;
    }

    public String field() {
        return field;
    }
}
