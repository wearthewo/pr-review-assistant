package io.prreviewassistant.usage;

import io.prreviewassistant.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcUsageAccountingStore implements UsageAccountingStore {
    private static final UsageType TYPE = UsageType.REVIEW_ANALYSIS;
    private final JdbcClient jdbc;

    public JdbcUsageAccountingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public UsageReservationResult reserve(TenantContext tenantContext, UUID reviewJobId,
            UsagePeriod period, QuotaPolicy quotaPolicy, Instant now) {
        requireInputs(tenantContext, reviewJobId, period, now);
        lockQuota(tenantContext.tenantId(), period);
        Optional<UsageStatus> existing = findStatus(tenantContext, reviewJobId);
        long used = countQuotaUsage(tenantContext.tenantId(), period);
        QuotaDecision decision = quotaPolicy.decide(used, period);
        if (existing.isPresent()) {
            return new UsageReservationResult(existingOutcome(existing.orElseThrow()), decision);
        }
        if (!decision.allowed()) {
            return new UsageReservationResult(UsageReservationResult.Outcome.QUOTA_EXCEEDED, decision);
        }
        jdbc.sql("""
                INSERT INTO tenant_usage_events
                  (id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                   occurred_at, created_at, updated_at)
                VALUES (:id, :tenantId, :tenantRepositoryId, :reviewJobId, :usageType, 'RESERVED',
                        :now, :now, :now)
                """).param("id", UUID.randomUUID())
                .param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .param("reviewJobId", reviewJobId)
                .param("usageType", TYPE.name())
                .param("now", utc(now)).update();
        return new UsageReservationResult(UsageReservationResult.Outcome.ACQUIRED, decision);
    }

    @Override
    @Transactional
    public void consume(TenantContext tenantContext, UUID reviewJobId,
            UsageMeasurement measurement, Instant now) {
        requireInputs(tenantContext, reviewJobId, null, now);
        if (measurement == null) {
            throw new IllegalArgumentException("measurement is required");
        }
        int updated = jdbc.sql("""
                UPDATE tenant_usage_events
                SET status = 'CONSUMED', consumed_at = :now, updated_at = :now,
                    provider = :provider, model = :model,
                    input_tokens = :inputTokens, cached_input_tokens = :cachedInputTokens,
                    output_tokens = :outputTokens, reasoning_tokens = :reasoningTokens,
                    total_tokens = :totalTokens
                WHERE tenant_id = :tenantId
                  AND tenant_repository_id = :tenantRepositoryId
                  AND review_job_id = :reviewJobId
                  AND usage_type = :usageType
                  AND status = 'RESERVED'
                """).param("now", utc(now))
                .param("provider", measurement.provider().orElse(null))
                .param("model", measurement.model().orElse(null))
                .param("inputTokens", boxed(measurement.inputTokens()))
                .param("cachedInputTokens", boxed(measurement.cachedInputTokens()))
                .param("outputTokens", boxed(measurement.outputTokens()))
                .param("reasoningTokens", boxed(measurement.reasoningTokens()))
                .param("totalTokens", boxed(measurement.totalTokens()))
                .param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .param("reviewJobId", reviewJobId)
                .param("usageType", TYPE.name()).update();
        if (updated == 0 && findStatus(tenantContext, reviewJobId)
                .filter(status -> status == UsageStatus.CONSUMED).isEmpty()) {
            throw new UsageAccountingException(UsageAccountingError.USAGE_TENANT_MISMATCH);
        }
    }

    @Override
    @Transactional
    public boolean release(TenantContext tenantContext, UUID reviewJobId, Instant now) {
        requireInputs(tenantContext, reviewJobId, null, now);
        return jdbc.sql("""
                UPDATE tenant_usage_events
                SET status = 'RELEASED', released_at = :now, updated_at = :now
                WHERE tenant_id = :tenantId
                  AND tenant_repository_id = :tenantRepositoryId
                  AND review_job_id = :reviewJobId
                  AND usage_type = :usageType
                  AND status = 'RESERVED'
                """).param("now", utc(now))
                .param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .param("reviewJobId", reviewJobId)
                .param("usageType", TYPE.name()).update() == 1;
    }

    @Override
    public QuotaDecision quota(UUID tenantId, UsagePeriod period, QuotaPolicy quotaPolicy) {
        if (tenantId == null || period == null || quotaPolicy == null) {
            throw new IllegalArgumentException("tenant, period, and quota policy are required");
        }
        return quotaPolicy.decide(countQuotaUsage(tenantId, period), period);
    }

    @Override
    public UsageSummary summarize(UUID tenantId, UsagePeriod period) {
        if (tenantId == null || period == null) {
            throw new IllegalArgumentException("tenant and period are required");
        }
        return jdbc.sql("""
                SELECT count(*) AS event_count,
                       count(input_tokens) AS input_count, sum(input_tokens) AS input_sum,
                       count(cached_input_tokens) AS cached_count, sum(cached_input_tokens) AS cached_sum,
                       count(output_tokens) AS output_count, sum(output_tokens) AS output_sum,
                       count(reasoning_tokens) AS reasoning_count, sum(reasoning_tokens) AS reasoning_sum,
                       count(total_tokens) AS total_count, sum(total_tokens) AS total_sum
                FROM tenant_usage_events
                WHERE tenant_id = :tenantId
                  AND usage_type = :usageType
                  AND status = 'CONSUMED'
                  AND occurred_at >= :periodStart
                  AND occurred_at < :periodEnd
                """).param("tenantId", tenantId).param("usageType", TYPE.name())
                .param("periodStart", utc(period.start())).param("periodEnd", utc(period.end()))
                .query((resultSet, row) -> summary(tenantId, period, resultSet)).single();
    }

    @Override
    public Optional<UsageEvent> findByTenantAndReviewJobId(UUID tenantId, UUID reviewJobId) {
        if (tenantId == null || reviewJobId == null) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                       occurred_at, consumed_at, released_at, provider, model,
                       input_tokens, cached_input_tokens, output_tokens, reasoning_tokens, total_tokens
                FROM tenant_usage_events
                WHERE tenant_id = :tenantId AND review_job_id = :reviewJobId AND usage_type = :usageType
                """).param("tenantId", tenantId).param("reviewJobId", reviewJobId)
                .param("usageType", TYPE.name()).query(this::event).optional();
    }

    private Optional<UsageStatus> findStatus(TenantContext tenantContext, UUID reviewJobId) {
        return jdbc.sql("""
                SELECT status FROM tenant_usage_events
                WHERE tenant_id = :tenantId
                  AND tenant_repository_id = :tenantRepositoryId
                  AND review_job_id = :reviewJobId
                  AND usage_type = :usageType
                """).param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId()).param("reviewJobId", reviewJobId)
                .param("usageType", TYPE.name()).query(String.class)
                .optional().map(UsageStatus::valueOf);
    }

    private long countQuotaUsage(UUID tenantId, UsagePeriod period) {
        return jdbc.sql("""
                SELECT count(*) FROM tenant_usage_events
                WHERE tenant_id = :tenantId AND usage_type = :usageType
                  AND status IN ('RESERVED', 'CONSUMED')
                  AND occurred_at >= :periodStart AND occurred_at < :periodEnd
                """).param("tenantId", tenantId).param("usageType", TYPE.name())
                .param("periodStart", utc(period.start())).param("periodEnd", utc(period.end()))
                .query(Long.class).single();
    }

    private void lockQuota(UUID tenantId, UsagePeriod period) {
        ZonedDateTime start = period.start().atZone(ZoneOffset.UTC);
        int monthKey = Math.addExact(Math.multiplyExact(start.getYear(), 100), start.getMonthValue());
        jdbc.sql("SELECT pg_advisory_xact_lock(:tenantKey, :monthKey)")
                .param("tenantKey", tenantId.hashCode()).param("monthKey", monthKey)
                .query((resultSet, row) -> Boolean.TRUE).single();
    }

    private static UsageReservationResult.Outcome existingOutcome(UsageStatus status) {
        return switch (status) {
            case RESERVED -> UsageReservationResult.Outcome.EXISTING_RESERVED;
            case CONSUMED -> UsageReservationResult.Outcome.EXISTING_CONSUMED;
            case RELEASED -> UsageReservationResult.Outcome.EXISTING_RELEASED;
        };
    }

    private UsageEvent event(ResultSet resultSet, int row) throws SQLException {
        UsageStatus status = UsageStatus.valueOf(resultSet.getString("status"));
        UsageMeasurement measurement = status == UsageStatus.CONSUMED
                ? new UsageMeasurement(optional(resultSet.getString("provider")), optional(resultSet.getString("model")),
                        optionalLong(resultSet, "input_tokens"), optionalLong(resultSet, "cached_input_tokens"),
                        optionalLong(resultSet, "output_tokens"), optionalLong(resultSet, "reasoning_tokens"),
                        optionalLong(resultSet, "total_tokens")) : null;
        return new UsageEvent(resultSet.getObject("id", UUID.class), resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("tenant_repository_id", UUID.class),
                resultSet.getObject("review_job_id", UUID.class), UsageType.valueOf(resultSet.getString("usage_type")),
                status, resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                optionalInstant(resultSet, "consumed_at"), optionalInstant(resultSet, "released_at"),
                Optional.ofNullable(measurement));
    }

    private static UsageSummary summary(UUID tenantId, UsagePeriod period, ResultSet resultSet) throws SQLException {
        long count = resultSet.getLong("event_count");
        return new UsageSummary(tenantId, period, count,
                MeasuredTotal.from(count, resultSet.getLong("input_count"), nullableLong(resultSet, "input_sum")),
                MeasuredTotal.from(count, resultSet.getLong("cached_count"), nullableLong(resultSet, "cached_sum")),
                MeasuredTotal.from(count, resultSet.getLong("output_count"), nullableLong(resultSet, "output_sum")),
                MeasuredTotal.from(count, resultSet.getLong("reasoning_count"), nullableLong(resultSet, "reasoning_sum")),
                MeasuredTotal.from(count, resultSet.getLong("total_count"), nullableLong(resultSet, "total_sum")));
    }

    private static void requireInputs(TenantContext tenantContext, UUID reviewJobId,
            UsagePeriod period, Instant now) {
        if (tenantContext == null || reviewJobId == null || now == null) {
            throw new IllegalArgumentException("usage accounting identity is required");
        }
        if (period != null && (now.isBefore(period.start()) || !now.isBefore(period.end()))) {
            throw new IllegalArgumentException("reservation time is outside usage period");
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Optional<String> optional(String value) {
        return Optional.ofNullable(value);
    }

    private static OptionalLong optionalLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static Optional<Instant> optionalInstant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? Optional.empty() : Optional.of(value.toInstant());
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long boxed(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }
}
