package io.prreviewassistant.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TenantMembership(
        UUID id,
        UUID tenantId,
        UUID userId,
        TenantMembershipRole role,
        Instant createdAt,
        Instant updatedAt) {
    public TenantMembership {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("membership timestamps are invalid");
        }
    }
}
