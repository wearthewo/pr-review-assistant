package io.prreviewassistant.usage;

import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import io.prreviewassistant.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import io.prreviewassistant.observability.ApplicationMetrics;

public final class UsageAccountingService {
    private final UsageAccountingStore store;
    private final QuotaPolicy quotaPolicy;
    private final Clock clock;
    private final ApplicationMetrics metrics;

    public UsageAccountingService(UsageAccountingStore store, QuotaPolicy quotaPolicy, Clock clock) {
        this(store, quotaPolicy, clock, ApplicationMetrics.noop());
    }

    public UsageAccountingService(UsageAccountingStore store, QuotaPolicy quotaPolicy, Clock clock,
            ApplicationMetrics metrics) {
        this.store = store;
        this.quotaPolicy = quotaPolicy;
        this.clock = clock;
        this.metrics = metrics;
    }

    public UsageReservationResult reserve(TenantContext tenantContext, UUID reviewJobId) {
        Instant now = clock.instant();
        try {
            UsageReservationResult result = store.reserve(
                    tenantContext, reviewJobId, UsagePeriod.utcMonthContaining(now), quotaPolicy, now);
            metrics.usage("reserve", switch (result.outcome()) {
                case ACQUIRED -> "accepted";
                case QUOTA_EXCEEDED -> "exhausted";
                case EXISTING_RESERVED -> "existing_reserved";
                case EXISTING_CONSUMED -> "existing_consumed";
                case EXISTING_RELEASED -> "existing_released";
            });
            return result;
        } catch (UsageAccountingException exception) {
            metrics.usage("reserve", "failure");
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            metrics.usage("reserve", "failure");
            throw new UsageAccountingException(UsageAccountingError.USAGE_TENANT_MISMATCH);
        } catch (DataAccessException exception) {
            metrics.usage("reserve", "failure");
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public void consume(TenantContext tenantContext, UUID reviewJobId, ReviewAnalysisMetadata metadata) {
        try {
            store.consume(tenantContext, reviewJobId, UsageMeasurement.from(metadata), clock.instant());
            metrics.usage("consume", "consumed");
        } catch (UsageAccountingException exception) {
            metrics.usage("consume", "failure");
            throw exception;
        } catch (DataIntegrityViolationException exception) {
            metrics.usage("consume", "failure");
            throw new UsageAccountingException(UsageAccountingError.USAGE_TENANT_MISMATCH);
        } catch (DataAccessException | IllegalArgumentException exception) {
            metrics.usage("consume", "failure");
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public boolean release(TenantContext tenantContext, UUID reviewJobId) {
        try {
            boolean released = store.release(tenantContext, reviewJobId, clock.instant());
            metrics.usage("release", released ? "released" : "not_released");
            return released;
        } catch (DataAccessException exception) {
            metrics.usage("release", "failure");
            throw new UsageAccountingException(UsageAccountingError.USAGE_ACCOUNTING_FAILED);
        }
    }

    public QuotaDecision quota(TenantContext tenantContext) {
        if (tenantContext == null) {
            throw new IllegalArgumentException("tenantContext is required");
        }
        return quota(tenantContext.tenantId());
    }

    public QuotaDecision quota(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Instant now = clock.instant();
        try {
            return store.quota(tenantId, UsagePeriod.utcMonthContaining(now), quotaPolicy);
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
