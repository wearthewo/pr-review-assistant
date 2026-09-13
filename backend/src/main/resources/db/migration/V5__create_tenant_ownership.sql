CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_tenants_time CHECK (updated_at >= created_at)
);

CREATE TABLE github_installations (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    github_installation_id BIGINT NOT NULL CHECK (github_installation_id > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_github_installations_external UNIQUE (github_installation_id),
    CONSTRAINT uq_github_installations_owner UNIQUE (id, tenant_id),
    CONSTRAINT ck_github_installations_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_github_installations_tenant ON github_installations (tenant_id, id);

CREATE TABLE tenant_repositories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    installation_id UUID NOT NULL,
    github_repository_id BIGINT NOT NULL CHECK (github_repository_id > 0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tenant_repositories_external UNIQUE (github_repository_id),
    CONSTRAINT uq_tenant_repositories_owner UNIQUE (id, tenant_id),
    CONSTRAINT uq_tenant_repositories_installation_owner UNIQUE (id, tenant_id, installation_id),
    CONSTRAINT fk_tenant_repositories_installation_owner
        FOREIGN KEY (installation_id, tenant_id)
        REFERENCES github_installations(id, tenant_id),
    CONSTRAINT ck_tenant_repositories_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_tenant_repositories_tenant ON tenant_repositories (tenant_id, id);
CREATE INDEX ix_tenant_repositories_installation ON tenant_repositories (installation_id, id);

ALTER TABLE review_jobs
    ADD COLUMN tenant_id UUID,
    ADD COLUMN tenant_repository_id UUID,
    ADD CONSTRAINT uq_review_jobs_tenant_owner UNIQUE (id, tenant_id),
    ADD CONSTRAINT ck_review_jobs_tenant_shape CHECK (
        (tenant_id IS NULL AND tenant_repository_id IS NULL)
        OR (tenant_id IS NOT NULL AND tenant_repository_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_review_jobs_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    ADD CONSTRAINT fk_review_jobs_repository_owner
        FOREIGN KEY (tenant_repository_id, tenant_id)
        REFERENCES tenant_repositories(id, tenant_id);

CREATE INDEX ix_review_jobs_tenant ON review_jobs (tenant_id, created_at, id);

ALTER TABLE review_publications
    ADD COLUMN tenant_id UUID,
    ADD COLUMN tenant_repository_id UUID,
    ADD CONSTRAINT ck_review_publications_tenant_shape CHECK (
        (tenant_id IS NULL AND tenant_repository_id IS NULL)
        OR (tenant_id IS NOT NULL AND tenant_repository_id IS NOT NULL)
    ),
    ADD CONSTRAINT fk_review_publications_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    ADD CONSTRAINT fk_review_publications_repository_owner
        FOREIGN KEY (tenant_repository_id, tenant_id)
        REFERENCES tenant_repositories(id, tenant_id),
    ADD CONSTRAINT fk_review_publications_analysis_owner
        FOREIGN KEY (analysis_job_id, tenant_id)
        REFERENCES review_jobs(id, tenant_id);

CREATE INDEX ix_review_publications_tenant ON review_publications (tenant_id, created_at, id);
