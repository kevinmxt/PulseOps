CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'ADMIN',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT chk_app_user_username_format CHECK (username ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT chk_app_user_role CHECK (role IN ('ADMIN'))
);
