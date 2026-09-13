package io.prreviewassistant.tenant;

import java.time.Instant;
import java.util.Optional;

public interface TenantOwnershipStore {
    TenantContext provision(long githubInstallationId, long githubRepositoryId, Instant now);
    Optional<TenantContext> resolve(long githubInstallationId, long githubRepositoryId);
}
