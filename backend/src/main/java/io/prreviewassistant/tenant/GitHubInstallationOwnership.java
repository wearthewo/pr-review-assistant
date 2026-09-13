package io.prreviewassistant.tenant;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record GitHubInstallationOwnership(UUID id, UUID tenantId, long githubInstallationId,
        Instant createdAt, Instant updatedAt) {
    public GitHubInstallationOwnership {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (githubInstallationId <= 0 || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("installation ownership is invalid");
        }
    }
}
