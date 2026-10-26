CREATE INDEX idx_check_result_started_at ON check_result (started_at);

CREATE INDEX idx_incident_resolved_at ON incident (resolved_at) WHERE status = 'RESOLVED';
