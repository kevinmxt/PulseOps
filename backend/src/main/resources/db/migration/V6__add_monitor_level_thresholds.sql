ALTER TABLE monitor
    ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_failures >= 0),
    ADD COLUMN consecutive_successes INTEGER NOT NULL DEFAULT 0 CHECK (consecutive_successes >= 0),
    ADD COLUMN failure_threshold INTEGER NOT NULL DEFAULT 3 CHECK (failure_threshold BETWEEN 1 AND 10),
    ADD COLUMN recovery_threshold INTEGER NOT NULL DEFAULT 2 CHECK (recovery_threshold BETWEEN 1 AND 10);
