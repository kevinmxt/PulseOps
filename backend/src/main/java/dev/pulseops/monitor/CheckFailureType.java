package dev.pulseops.monitor;

public enum CheckFailureType {
    CONNECTION_FAILED("连接失败"),
    DNS_RESOLVER_EXHAUSTED("DNS 解析器不可用"),
    CONNECT_TIMEOUT("连接超时"),
    RESPONSE_TIMEOUT("响应超时"),
    UNEXPECTED_STATUS("非预期状态码"),
    INTERNAL_ERROR("内部探测异常"),
    HEARTBEAT_TIMEOUT("心跳超时");

    private final String label;

    CheckFailureType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
