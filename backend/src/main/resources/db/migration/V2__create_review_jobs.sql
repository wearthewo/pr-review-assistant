CREATE TABLE review_jobs (
    id UUID PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    claim_token UUID,
    claimed_at TIMESTAMP WITH TIME ZONE,
    claim_expires_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_review_jobs_status
        CHECK (status IN ('READY', 'PROCESSING', 'COMPLETED', 'FAILED')),
    CONSTRAINT ck_review_jobs_attempts
        CHECK (attempts >= 0 AND max_attempts > 0 AND attempts <= max_attempts),
    CONSTRAINT ck_review_jobs_error_code
        CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_review_jobs_timestamp_order
        CHECK (updated_at >= created_at),
    CONSTRAINT ck_review_jobs_state_shape CHECK (
        (status = 'READY'
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND claim_expires_at IS NULL
            AND completed_at IS NULL
            AND failed_at IS NULL)
        OR
        (status = 'PROCESSING'
            AND next_attempt_at IS NOT NULL
            AND claim_token IS NOT NULL
            AND claimed_at IS NOT NULL
            AND claim_expires_at IS NOT NULL
            AND claim_expires_at > claimed_at
            AND completed_at IS NULL
            AND failed_at IS NULL)
        OR
        (status = 'COMPLETED'
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND claim_expires_at IS NULL
            AND completed_at IS NOT NULL
            AND failed_at IS NULL)
        OR
        (status = 'FAILED'
            AND next_attempt_at IS NULL
            AND claim_token IS NULL
            AND claimed_at IS NULL
            AND claim_expires_at IS NULL
            AND completed_at IS NULL
            AND failed_at IS NOT NULL)
    )
);

CREATE INDEX ix_review_jobs_ready_poll
    ON review_jobs (next_attempt_at, created_at, id)
    WHERE status = 'READY';

CREATE INDEX ix_review_jobs_expired_claims
    ON review_jobs (claim_expires_at, created_at, id)
    WHERE status = 'PROCESSING';
