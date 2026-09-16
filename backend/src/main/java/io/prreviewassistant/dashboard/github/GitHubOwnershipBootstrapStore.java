package io.prreviewassistant.dashboard.github;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GitHubOwnershipBootstrapStore {
    Optional<UUID> findTenantForInstallation(long githubInstallationId);

    MembershipBindingResult bindOwner(UUID tenantId, UUID applicationUserId, Instant now);

    enum MembershipBindingResult { CREATED, ALREADY_MEMBER, OWNERSHIP_CONFLICT }
}
