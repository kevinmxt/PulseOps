ALTER TABLE monitor
    DROP CONSTRAINT monitor_type_check,
    DROP CONSTRAINT monitor_interval_seconds_check,
    ALTER COLUMN target_url DROP NOT NULL,
    ALTER COLUMN method DROP NOT NULL,
    ALTER COLUMN connect_timeout_ms DROP NOT NULL,
    ALTER COLUMN response_timeout_ms DROP NOT NULL,
    ALTER COLUMN expected_status_min DROP NOT NULL,
    ALTER COLUMN expected_status_max DROP NOT NULL,
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN heartbeat_grace_seconds INTEGER;

ALTER TABLE monitor
    ADD CONSTRAINT chk_monitor_type CHECK (type IN ('HTTP', 'HEARTBEAT')),
    ADD CONSTRAINT chk_monitor_interval_by_type CHECK (
        (type = 'HTTP' AND interval_seconds BETWEEN 10 AND 86400)
        OR (type = 'HEARTBEAT' AND interval_seconds BETWEEN 30 AND 2592000)
    ),
    ADD CONSTRAINT chk_monitor_type_config CHECK (
        (type = 'HTTP'
            AND target_url IS NOT NULL
            AND method IS NOT NULL
            AND connect_timeout_ms IS NOT NULL
            AND response_timeout_ms IS NOT NULL
            AND expected_status_min IS NOT NULL
            AND expected_status_max IS NOT NULL)
        OR (type = 'HEARTBEAT'
            AND heartbeat_grace_seconds BETWEEN 0 AND 86400)
    );

CREATE TABLE heartbeat_credential (
    id BIGSERIAL PRIMARY KEY,
    monitor_id BIGINT NOT NULL REFERENCES monitor (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT chk_heartbeat_credential_revoked_after_created CHECK (
        revoked_at IS NULL OR revoked_at >= created_at
    )
);

CREATE UNIQUE INDEX uq_heartbeat_credential_token_hash ON heartbeat_credential (token_hash);
CREATE UNIQUE INDEX uq_heartbeat_credential_active_monitor
    ON heartbeat_credential (monitor_id) WHERE revoked_at IS NULL;
CREATE INDEX idx_heartbeat_credential_monitor ON heartbeat_credential (monitor_id);
