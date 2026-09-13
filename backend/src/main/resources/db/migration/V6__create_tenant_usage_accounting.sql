ALTER TABLE review_jobs
    ADD CONSTRAINT uq_review_jobs_usage_owner
        UNIQUE (id, tenant_id, tenant_repository_id);

CREATE TABLE tenant_usage_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    tenant_repository_id UUID NOT NULL,
    review_job_id UUID NOT NULL,
    usage_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP WITH TIME ZONE,
    released_at TIMESTAMP WITH TIME ZONE,
    provider VARCHAR(64),
    model VARCHAR(200),
    input_tokens BIGINT,
    cached_input_tokens BIGINT,
    output_tokens BIGINT,
    reasoning_tokens BIGINT,
    total_tokens BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tenant_usage_logical UNIQUE (review_job_id, usage_type),
    CONSTRAINT fk_tenant_usage_review_owner
        FOREIGN KEY (review_job_id, tenant_id, tenant_repository_id)
        REFERENCES review_jobs(id, tenant_id, tenant_repository_id),
    CONSTRAINT ck_tenant_usage_type CHECK (usage_type = 'REVIEW_ANALYSIS'),
    CONSTRAINT ck_tenant_usage_status CHECK (status IN ('RESERVED', 'CONSUMED', 'RELEASED')),
    CONSTRAINT ck_tenant_usage_times CHECK (
        updated_at >= created_at
        AND occurred_at >= created_at
        AND (consumed_at IS NULL OR consumed_at >= occurred_at)
        AND (released_at IS NULL OR released_at >= occurred_at)
        AND (
            (status = 'RESERVED' AND consumed_at IS NULL AND released_at IS NULL)
            OR (status = 'CONSUMED' AND consumed_at IS NOT NULL AND released_at IS NULL)
            OR (status = 'RELEASED' AND consumed_at IS NULL AND released_at IS NOT NULL)
        )
    ),
    CONSTRAINT ck_tenant_usage_metadata CHECK (
        (status = 'CONSUMED')
        OR (provider IS NULL AND model IS NULL
            AND input_tokens IS NULL AND cached_input_tokens IS NULL
            AND output_tokens IS NULL AND reasoning_tokens IS NULL AND total_tokens IS NULL)
    ),
    CONSTRAINT ck_tenant_usage_provider CHECK (
        provider IS NULL OR provider ~ '^[A-Za-z0-9._:/-]+$'
    ),
    CONSTRAINT ck_tenant_usage_model CHECK (
        model IS NULL OR model ~ '^[A-Za-z0-9._:/-]+$'
    ),
    CONSTRAINT ck_tenant_usage_input_tokens CHECK (input_tokens BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_tenant_usage_cached_tokens CHECK (cached_input_tokens BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_tenant_usage_output_tokens CHECK (output_tokens BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_tenant_usage_reasoning_tokens CHECK (reasoning_tokens BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_tenant_usage_total_tokens CHECK (total_tokens BETWEEN 0 AND 1000000000000),
    CONSTRAINT ck_tenant_usage_cached_within_input CHECK (
        input_tokens IS NULL OR cached_input_tokens IS NULL OR cached_input_tokens <= input_tokens
    ),
    CONSTRAINT ck_tenant_usage_reasoning_within_output CHECK (
        output_tokens IS NULL OR reasoning_tokens IS NULL OR reasoning_tokens <= output_tokens
    )
);

CREATE INDEX ix_tenant_usage_period
    ON tenant_usage_events (tenant_id, occurred_at);

CREATE INDEX ix_tenant_usage_type_period
    ON tenant_usage_events (tenant_id, usage_type, occurred_at);

CREATE INDEX ix_tenant_usage_status_period
    ON tenant_usage_events (tenant_id, status, occurred_at);
