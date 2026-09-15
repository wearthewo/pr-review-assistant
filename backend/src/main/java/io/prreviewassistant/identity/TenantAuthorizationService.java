package io.prreviewassistant.identity;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class TenantAuthorizationService {
    private final ApplicationIdentityStore store;
    private final Clock clock;

    public TenantAuthorizationService(ApplicationIdentityStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    public DashboardSession session(AuthenticatedUserIdentity identity) {
        ApplicationUser user = store.provisionUser(
                Objects.requireNonNull(identity, "identity is required"), clock.instant());
        return new DashboardSession(user, store.findMemberships(user.id()));
    }

    public AuthorizedTenantContext authorize(AuthenticatedUserIdentity identity, UUID requestedTenantId) {
        Objects.requireNonNull(identity, "identity is required");
        Objects.requireNonNull(requestedTenantId, "requestedTenantId is required");
        ApplicationUser user = store.provisionUser(identity, clock.instant());
        return store.authorize(user.id(), requestedTenantId)
                .orElseThrow(TenantAccessDeniedException::new);
    }
}
