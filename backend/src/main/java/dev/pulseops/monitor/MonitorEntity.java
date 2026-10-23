package dev.pulseops.monitor;

import dev.pulseops.monitor.config.HttpMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "monitor")
public class MonitorEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MonitorType type;

    @Column(name = "target_url", nullable = false, length = 2048)
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HttpMethod method;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs;

    @Column(name = "response_timeout_ms", nullable = false)
    private int responseTimeoutMs;

    @Column(name = "expected_status_min", nullable = false)
    private int expectedStatusMin;

    @Column(name = "expected_status_max", nullable = false)
    private int expectedStatusMax;

    @Column(nullable = false)
    private boolean enabled;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_status", nullable = false, length = 10)
    private MonitorStatus currentStatus;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "next_check_at", nullable = false)
    private Instant nextCheckAt;

    @Column(name = "config_version", nullable = false)
    private long configVersion = 1;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "consecutive_successes", nullable = false)
    private int consecutiveSuccesses;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold = 3;

    @Column(name = "recovery_threshold", nullable = false)
    private int recoveryThreshold = 2;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MonitorType getType() {
        return type;
    }

    public void setType(MonitorType type) {
        this.type = type;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public void setTargetUrl(String targetUrl) {
        this.targetUrl = targetUrl;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getResponseTimeoutMs() {
        return responseTimeoutMs;
    }

    public void setResponseTimeoutMs(int responseTimeoutMs) {
        this.responseTimeoutMs = responseTimeoutMs;
    }

    public int getExpectedStatusMin() {
        return expectedStatusMin;
    }

    public void setExpectedStatusMin(int expectedStatusMin) {
        this.expectedStatusMin = expectedStatusMin;
    }

    public int getExpectedStatusMax() {
        return expectedStatusMax;
    }

    public void setExpectedStatusMax(int expectedStatusMax) {
        this.expectedStatusMax = expectedStatusMax;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MonitorStatus getCurrentStatus() {
        return currentStatus;
    }

    public void setCurrentStatus(MonitorStatus currentStatus) {
        this.currentStatus = currentStatus;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Instant getNextCheckAt() {
        return nextCheckAt;
    }

    public void setNextCheckAt(Instant nextCheckAt) {
        this.nextCheckAt = nextCheckAt;
    }

    public long getConfigVersion() {
        return configVersion;
    }

    void incrementConfigVersion() {
        configVersion++;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public void setConsecutiveSuccesses(int consecutiveSuccesses) {
        this.consecutiveSuccesses = consecutiveSuccesses;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public int getRecoveryThreshold() {
        return recoveryThreshold;
    }

    public void setRecoveryThreshold(int recoveryThreshold) {
        this.recoveryThreshold = recoveryThreshold;
    }

    public void recordProbeOutcome(boolean success) {
        if (success) {
            consecutiveSuccesses++;
            consecutiveFailures = 0;
        } else {
            consecutiveFailures++;
            consecutiveSuccesses = 0;
        }
        currentStatus = nextStatus();
    }

    private MonitorStatus nextStatus() {
        if (consecutiveFailures >= failureThreshold) {
            return MonitorStatus.DOWN;
        }
        if (consecutiveSuccesses >= recoveryThreshold) {
            return MonitorStatus.UP;
        }
        if (currentStatus == MonitorStatus.UNKNOWN && consecutiveSuccesses > 0) {
            return MonitorStatus.UP;
        }
        return currentStatus;
    }

    public void resetConsecutiveCounters() {
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
