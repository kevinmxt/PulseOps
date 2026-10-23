CREATE TABLE notification_delivery (
    id BIGSERIAL PRIMARY KEY,
    incident_id BIGINT NOT NULL REFERENCES incident (id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL CHECK (channel IN ('TELEGRAM')),
    event_type VARCHAR(30) NOT NULL CHECK (event_type IN ('INCIDENT_OPENED', 'INCIDENT_RESOLVED')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    sent_at TIMESTAMPTZ,
    last_error_summary VARCHAR(500),
    CONSTRAINT uq_notification_delivery_event UNIQUE (incident_id, event_type, channel),
    CONSTRAINT chk_notification_delivery_sent CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

CREATE INDEX idx_notification_delivery_due ON notification_delivery (status, next_attempt_at);
