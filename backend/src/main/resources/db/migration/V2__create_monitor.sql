CREATE TABLE monitor (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL DEFAULT 'HTTP' CHECK (type IN ('HTTP')),
    target_url VARCHAR(2048) NOT NULL,
    method VARCHAR(10) NOT NULL DEFAULT 'GET' CHECK (method IN ('GET', 'HEAD')),
    interval_seconds INTEGER NOT NULL CHECK (interval_seconds BETWEEN 10 AND 86400),
    connect_timeout_ms INTEGER NOT NULL CHECK (connect_timeout_ms BETWEEN 1000 AND 10000),
    response_timeout_ms INTEGER NOT NULL CHECK (response_timeout_ms BETWEEN 1000 AND 30000),
    expected_status_min INTEGER NOT NULL CHECK (expected_status_min BETWEEN 100 AND 599),
    expected_status_max INTEGER NOT NULL CHECK (expected_status_max BETWEEN 100 AND 599),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    current_status VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN' CHECK (current_status IN ('UNKNOWN', 'UP', 'DOWN')),
    last_checked_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    next_check_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_monitor_name_not_blank CHECK (CHAR_LENGTH(BTRIM(name)) > 0),
    CONSTRAINT chk_monitor_expected_status_range CHECK (expected_status_min <= expected_status_max)
);

CREATE INDEX idx_monitor_next_check_at ON monitor (next_check_at) WHERE enabled;
