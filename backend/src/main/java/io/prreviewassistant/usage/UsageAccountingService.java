package io.prreviewassistant.usage;

import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import io.prreviewassistant.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

public final class UsageAccountingService {
    private final UsageAccountingStore store;
    private final QuotaPolicy quotaPolicy;
    private final Clock clock;

    public UsageAccountingService(UsageAccountingStore store, QuotaPolicy quotaPolicy, Clock clock) {
        this.store = store;
        this.quotaPolicy = quotaPolicy;
        this.clock = clock;
    }

    public UsageReservationResult reserve(TenantContext tenantContext, UUID reviewJobId) {
        Instant now = clock.instant();
        try {
            return store.reserve(tenantContext, reviewJobId, UsagePeriod.utcMonthContaining(now), quotaPolicy, now);
        } catch (UsageAccountingException exception) {
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_TENANT_MISMATCH);
        } catch (DataAccessException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public void consume(TenantContext tenantContext, UUID reviewJobId, ReviewAnalysisMetadata metadata) {
        try {
            store.consume(tenantContext, reviewJobId, UsageMeasurement.from(metadata), clock.instant());
        } catch (UsageAccountingException exception) {
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_TENANT_MISMATCH);
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public boolean release(TenantContext tenantContext, UUID reviewJobId) {
        try {
            return store.release(tenantContext, reviewJobId, clock.instant());
        } catch (DataAccessException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public QuotaDecision quota(TenantContext tenantContext) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext is required");
        }
        Instant now = clock.instant();
        try {
            return store.quota(tenantContext.tenantId(), UsagePeriod.utcMonthContaining(now), quotaPolicy);
        } catch (DataAccessException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public UsageSummary summarize(TenantContext tenantContext) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext is required");
        }
        Instant now = clock.instant();
        try {
            return store.summarize(tenantContext.tenantId(), UsagePeriod.utcMonthContaining(now));
        } catch (DataAccessException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public Optional<UsageEvent> find(TenantContext tenantContext, UUID reviewJobId) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext is required");
        }
        try {
            return store.findByTenantAndReviewJobId(tenantContext.tenantId(), reviewJobId);
        } catch (DataAccessException exception) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }
}
