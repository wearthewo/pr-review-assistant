package io.prreviewassistant.dashboard.usage;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.AuthorizedTenantContext;
import io.prreviewassistant.identity.TenantAuthorizationService;
import io.prreviewassistant.usage.QuotaDecision;
import io.prreviewassistant.usage.UsageAccountingService;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DashboardUsageService {
    private final TenantAuthorizationService authorization;
    private final UsageAccountingService accounting;

    public DashboardUsageService(
            TenantAuthorizationService authorization,
            UsageAccountingService accounting) {
        this.authorization = Objects.requireNonNull(authorization, "authorization is required");
        this.accounting = Objects.requireNonNull(accounting, "accounting is required");
    }

    public UsageResponse current(AuthenticatedUserIdentity identity, UUID requestedTenantId) {
        AuthorizedTenantContext context = authorization.authorize(identity, requestedTenantId);
        QuotaDecision quota = accounting.quota(context.tenantId());
        return new UsageResponse(new ReviewAnalysisUsage(
                quota.period().start(), quota.period().end(), quota.limit(), quota.used(), quota.remaining()));
    }

    public record UsageResponse(ReviewAnalysisUsage reviewAnalysis) {
        public UsageResponse {
            Objects.requireNonNull(reviewAnalysis, "reviewAnalysis is required");
        }
    }

    public record ReviewAnalysisUsage(
            Instant periodStart,
            Instant periodEnd,
            int limit,
            long used,
            long remaining) {
        public ReviewAnalysisUsage {
            Objects.requireNonNull(periodStart, "periodStart is required");
            Objects.requireNonNull(periodEnd, "periodEnd is required");
            if (!periodStart.isBefore(periodEnd) || limit < 1 || used < 0 || remaining < 0
                    || remaining > limit || remaining != Math.max(0L, (long) limit - used)) {
                throw new IllegalArgumentException("review analysis usage is invalid");
            }
        }
    }
}
