package io.prreviewassistant.usage;

import io.prreviewassistant.tenant.TenantContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UsageAccountingStore {
    UsageReservationResult reserve(TenantContext tenantContext, UUID reviewJobId,
            UsagePeriod period, QuotaPolicy quotaPolicy, Instant now);

    void consume(TenantContext tenantContext, UUID reviewJobId,
            UsageMeasurement measurement, Instant now);

    boolean release(TenantContext tenantContext, UUID reviewJobId, Instant now);

    QuotaDecision quota(UUID tenantId, UsagePeriod period, QuotaPolicy quotaPolicy);

    UsageSummary summarize(UUID tenantId, UsagePeriod period);

    Optional<UsageEvent> findByTenantAndReviewJobId(UUID tenantId, UUID reviewJobId);
}
