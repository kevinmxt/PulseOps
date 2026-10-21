CREATE TABLE check_result (
    id BIGSERIAL PRIMARY KEY,
    monitor_id BIGINT NOT NULL REFERENCES monitor (id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL,
    duration_ms BIGINT NOT NULL CHECK (duration_ms >= 0),
    success BOOLEAN NOT NULL,
    failure_type VARCHAR(30) CHECK (failure_type IN (
        'CONNECTION_FAILED', 'CONNECT_TIMEOUT', 'RESPONSE_TIMEOUT', 'UNEXPECTED_STATUS', 'INTERNAL_ERROR'
    )),
    http_status_code INTEGER CHECK (http_status_code BETWEEN 100 AND 599),
    error_summary VARCHAR(500),
    CONSTRAINT chk_check_result_outcome CHECK (
        (success AND failure_type IS NULL) OR (NOT success AND failure_type IS NOT NULL)
    )
);

CREATE INDEX idx_check_result_monitor_started_at ON check_result (monitor_id, started_at DESC);
