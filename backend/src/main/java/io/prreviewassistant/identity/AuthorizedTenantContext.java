package io.prreviewassistant.identity;

import java.util.Objects;
import java.util.UUID;

public final class AuthorizedTenantContext {
    private final UUID applicationUserId;
    private final UUID tenantId;
    private final TenantMembershipRole role;

    AuthorizedTenantContext(UUID applicationUserId, UUID tenantId, TenantMembershipRole role) {
        this.applicationUserId = Objects.requireNonNull(applicationUserId, "applicationUserId is required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId is required");
        this.role = Objects.requireNonNull(role, "role is required");
    }

    public UUID applicationUserId() {
        return applicationUserId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public TenantMembershipRole role() {
        return role;
    }

    @Override
    public String toString() {
        return "AuthorizedTenantContext[applicationUserId=" + applicationUserId
                + ", tenantId=" + tenantId + ", role=" + role + "]";
    }
}
