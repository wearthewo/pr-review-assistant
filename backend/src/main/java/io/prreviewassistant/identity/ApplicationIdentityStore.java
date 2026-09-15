package io.prreviewassistant.identity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationIdentityStore {
    ApplicationUser provisionUser(AuthenticatedUserIdentity identity, Instant now);

    List<TenantMembership> findMemberships(UUID applicationUserId);

    Optional<AuthorizedTenantContext> authorize(UUID applicationUserId, UUID tenantId);
}
