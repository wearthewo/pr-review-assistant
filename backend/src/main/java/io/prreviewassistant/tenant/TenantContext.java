package io.prreviewassistant.tenant;

import java.util.Objects;
import java.util.UUID;

public record TenantContext(UUID tenantId, UUID installationId, UUID repositoryId,
        long githubInstallationId, long githubRepositoryId) {
    public TenantContext {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(installationId, "installationId is required");
        Objects.requireNonNull(repositoryId, "repositoryId is required");
        if (githubInstallationId <= 0 || githubRepositoryId <= 0) {
            throw new IllegalArgumentException("GitHub identities must be positive");
        }
    }

    @Override
    public String toString() {
        return "TenantContext[tenantId=" + tenantId + ", ownership=<redacted>]";
    }
}
