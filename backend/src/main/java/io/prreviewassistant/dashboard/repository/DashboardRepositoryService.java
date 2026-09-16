package io.prreviewassistant.dashboard.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.AuthorizedTenantContext;
import io.prreviewassistant.identity.TenantAuthorizationService;
import org.springframework.stereotype.Service;

@Service
public class DashboardRepositoryService {
    static final int MAX_RESULTS = 100;
    static final int QUERY_LIMIT = MAX_RESULTS + 1;

    private final TenantAuthorizationService authorization;
    private final DashboardRepositoryStore repositories;

    public DashboardRepositoryService(
            TenantAuthorizationService authorization,
            DashboardRepositoryStore repositories) {
        this.authorization = authorization;
        this.repositories = repositories;
    }

    public RepositoryPage list(AuthenticatedUserIdentity identity, UUID requestedTenantId) {
        AuthorizedTenantContext context = authorization.authorize(identity, requestedTenantId);
        List<DashboardRepositoryStore.RepositoryRecord> found =
                repositories.findByTenant(context.tenantId(), QUERY_LIMIT);
        boolean truncated = found.size() > MAX_RESULTS;
        List<RepositorySummary> visible = found.stream().limit(MAX_RESULTS)
                .map(record -> new RepositorySummary(record.githubRepositoryId(), record.connectedAt()))
                .toList();
        return new RepositoryPage(visible, truncated);
    }

    public record RepositoryPage(List<RepositorySummary> repositories, boolean truncated) {
        public RepositoryPage {
            repositories = List.copyOf(repositories);
            if (repositories.size() > MAX_RESULTS) {
                throw new IllegalArgumentException("repository page exceeds its bound");
            }
        }
    }

    public record RepositorySummary(long repositoryId, Instant connectedAt) {
        public RepositorySummary {
            if (repositoryId <= 0 || connectedAt == null) {
                throw new IllegalArgumentException("repository summary is invalid");
            }
        }
    }
}
