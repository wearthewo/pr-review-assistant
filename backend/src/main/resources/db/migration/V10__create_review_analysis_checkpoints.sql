CREATE TABLE review_analysis_checkpoints (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    tenant_repository_id UUID NOT NULL,
    review_job_id UUID NOT NULL,
    payload_version INTEGER NOT NULL CHECK (payload_version = 1),
    finding_count INTEGER NOT NULL CHECK (finding_count BETWEEN 0 AND 10),
    findings_payload TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_review_analysis_checkpoints_job UNIQUE (review_job_id),
    CONSTRAINT fk_review_analysis_checkpoints_owner
        FOREIGN KEY (review_job_id, tenant_id, tenant_repository_id)
        REFERENCES review_jobs(id, tenant_id, tenant_repository_id),
    CONSTRAINT ck_review_analysis_checkpoints_payload CHECK (
        octet_length(findings_payload) BETWEEN 2 AND 262144
    ),
    CONSTRAINT ck_review_analysis_checkpoints_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_review_analysis_checkpoints_tenant
    ON review_analysis_checkpoints (tenant_id, review_job_id);
