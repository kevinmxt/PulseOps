ALTER TABLE check_result
    DROP CONSTRAINT check_result_failure_type_check,
    ADD CONSTRAINT check_result_failure_type_check CHECK (failure_type IN (
        'CONNECTION_FAILED',
        'DNS_RESOLVER_EXHAUSTED',
        'CONNECT_TIMEOUT',
        'RESPONSE_TIMEOUT',
        'UNEXPECTED_STATUS',
        'INTERNAL_ERROR',
        'HEARTBEAT_TIMEOUT'
    ));

ALTER TABLE incident
    DROP CONSTRAINT incident_failure_type_check,
    ADD CONSTRAINT incident_failure_type_check CHECK (failure_type IN (
        'CONNECTION_FAILED',
        'DNS_RESOLVER_EXHAUSTED',
        'CONNECT_TIMEOUT',
        'RESPONSE_TIMEOUT',
        'UNEXPECTED_STATUS',
        'INTERNAL_ERROR',
        'HEARTBEAT_TIMEOUT'
    ));
