package io.prreviewassistant.tenant;

import java.time.Clock;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public final class TenantOwnershipService {
    private final TenantOwnershipStore store;
    private final Clock clock;

    public TenantOwnershipService(TenantOwnershipStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public TenantContext provision(long githubInstallationId, long githubRepositoryId) {
        return store.provision(githubInstallationId, githubRepositoryId, clock.instant());
    }

    public Optional<TenantContext> resolve(long githubInstallationId, long githubRepositoryId) {
        return store.resolve(githubInstallationId, githubRepositoryId);
    }
}
