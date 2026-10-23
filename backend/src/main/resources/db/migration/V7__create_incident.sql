CREATE TABLE incident (
    id BIGSERIAL PRIMARY KEY,
    monitor_id BIGINT NOT NULL REFERENCES monitor (id) ON DELETE CASCADE,
    started_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    status VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    failure_type VARCHAR(30) NOT NULL CHECK (failure_type IN (
        'CONNECTION_FAILED',
        'DNS_RESOLVER_EXHAUSTED',
        'CONNECT_TIMEOUT',
        'RESPONSE_TIMEOUT',
        'UNEXPECTED_STATUS',
        'INTERNAL_ERROR'
    )),
    summary VARCHAR(200) NOT NULL,
    CONSTRAINT chk_incident_resolution CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status = 'RESOLVED' AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_incident_open_per_monitor ON incident (monitor_id) WHERE resolved_at IS NULL;
CREATE INDEX idx_incident_monitor_started_at ON incident (monitor_id, started_at DESC);
CREATE INDEX idx_incident_started_at ON incident (started_at DESC);
