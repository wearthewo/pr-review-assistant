CREATE TABLE review_publications (
    id UUID PRIMARY KEY,
    analysis_job_id UUID NOT NULL REFERENCES review_jobs(id),
    github_installation_id BIGINT NOT NULL CHECK (github_installation_id > 0),
    github_repository_id BIGINT NOT NULL CHECK (github_repository_id > 0),
    github_repository_owner VARCHAR(100) NOT NULL CHECK (github_repository_owner <> ''),
    github_repository_name VARCHAR(100) NOT NULL CHECK (github_repository_name <> ''),
    github_pull_request_number INTEGER NOT NULL CHECK (github_pull_request_number > 0),
    github_head_sha VARCHAR(64) NOT NULL CHECK (github_head_sha ~ '^[0-9a-f]{40,64}$'),
    publication_key CHAR(64) NOT NULL UNIQUE CHECK (publication_key ~ '^[0-9a-f]{64}$'),
    payload_version INTEGER NOT NULL CHECK (payload_version = 1),
    finding_count INTEGER NOT NULL CHECK (finding_count > 0 AND finding_count <= 5),
    payload TEXT NOT NULL CHECK (char_length(payload) BETWEEN 1 AND 100000),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'AMBIGUOUS', 'PUBLISHED', 'FAILED')),
    github_review_id BIGINT CHECK (github_review_id IS NULL OR github_review_id > 0),
    published_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_review_publications_analysis_job UNIQUE (analysis_job_id),
    CONSTRAINT ck_review_publications_state CHECK (
        (status = 'PUBLISHED' AND github_review_id IS NOT NULL AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND github_review_id IS NULL AND published_at IS NULL)
    ),
    CONSTRAINT ck_review_publications_time CHECK (updated_at >= created_at)
);

CREATE TABLE publication_jobs (
    id UUID PRIMARY KEY,
    publication_id UUID NOT NULL UNIQUE REFERENCES review_publications(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY', 'PROCESSING', 'COMPLETED', 'FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE,
    claim_token UUID,
    claimed_at TIMESTAMP WITH TIME ZONE,
    claim_expires_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    last_error_code VARCHAR(64) CHECK (last_error_code IS NULL OR last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK (attempts >= 0 AND max_attempts > 0 AND attempts <= max_attempts),
    CHECK (updated_at >= created_at),
    CONSTRAINT ck_publication_jobs_state CHECK (
        (status = 'READY' AND next_attempt_at IS NOT NULL AND claim_token IS NULL AND claimed_at IS NULL
            AND claim_expires_at IS NULL AND completed_at IS NULL AND failed_at IS NULL)
        OR (status = 'PROCESSING' AND next_attempt_at IS NOT NULL AND claim_token IS NOT NULL
            AND claimed_at IS NOT NULL AND claim_expires_at IS NOT NULL AND claim_expires_at > claimed_at
            AND completed_at IS NULL AND failed_at IS NULL)
        OR (status = 'COMPLETED' AND next_attempt_at IS NULL AND claim_token IS NULL AND claimed_at IS NULL
            AND claim_expires_at IS NULL AND completed_at IS NOT NULL AND failed_at IS NULL)
        OR (status = 'FAILED' AND next_attempt_at IS NULL AND claim_token IS NULL AND claimed_at IS NULL
            AND claim_expires_at IS NULL AND completed_at IS NULL AND failed_at IS NOT NULL)
    )
);

CREATE INDEX ix_publication_jobs_ready_poll ON publication_jobs (next_attempt_at, created_at, id)
    WHERE status = 'READY';
CREATE INDEX ix_publication_jobs_expired_claims ON publication_jobs (claim_expires_at, created_at, id)
    WHERE status = 'PROCESSING';
