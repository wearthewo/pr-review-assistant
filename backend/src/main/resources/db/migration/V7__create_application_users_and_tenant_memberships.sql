CREATE TABLE application_users (
    id UUID PRIMARY KEY,
    auth_issuer VARCHAR(2048) NOT NULL,
    auth_subject VARCHAR(512) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_application_users_external_identity UNIQUE (auth_issuer, auth_subject),
    CONSTRAINT ck_application_users_issuer CHECK (
        char_length(auth_issuer) BETWEEN 1 AND 2048
        AND auth_issuer !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_application_users_subject CHECK (
        char_length(auth_subject) BETWEEN 1 AND 512
        AND auth_subject !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_application_users_time CHECK (updated_at >= created_at)
);

CREATE TABLE tenant_memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES application_users(id),
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_tenant_memberships_time CHECK (updated_at >= created_at)
);

CREATE INDEX ix_tenant_memberships_user ON tenant_memberships (user_id, tenant_id);
CREATE INDEX ix_tenant_memberships_tenant ON tenant_memberships (tenant_id, user_id);
