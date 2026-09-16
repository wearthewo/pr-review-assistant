CREATE TABLE github_connection_states (
    state_hash CHAR(64) PRIMARY KEY,
    application_user_id UUID NOT NULL REFERENCES application_users(id) ON DELETE CASCADE,
    pkce_verifier VARCHAR(128) NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_github_connection_states_hash CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_github_connection_states_verifier CHECK (
        char_length(pkce_verifier) BETWEEN 43 AND 128
        AND pkce_verifier ~ '^[A-Za-z0-9._~-]+$'
    ),
    CONSTRAINT ck_github_connection_states_time CHECK (
        expires_at > created_at
        AND (consumed_at IS NULL OR consumed_at >= created_at)
    )
);

CREATE INDEX ix_github_connection_states_expiry
    ON github_connection_states (expires_at, state_hash);
