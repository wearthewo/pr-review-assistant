package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import io.prreviewassistant.usage.JdbcUsageAccountingStore;
import io.prreviewassistant.usage.QuotaPolicy;
import io.prreviewassistant.usage.UsageAccountingError;
import io.prreviewassistant.usage.UsageAccountingException;
import io.prreviewassistant.usage.UsageAccountingService;
import io.prreviewassistant.usage.UsageMeasurement;
import io.prreviewassistant.usage.UsagePeriod;
import io.prreviewassistant.usage.UsageReservationResult;
import io.prreviewassistant.usage.UsageStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=false",
        "REVIEW_PUBLICATION_ENABLED=false",
        "REVIEW_USAGE_MONTHLY_LIMIT=50",
        "DB_JDBC_URL=jdbc:postgresql://unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1",
        "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class UsageAccountingPersistenceIntegrationTest {
    private static final Instant SEPTEMBER = Instant.parse("2026-09-15T12:00:00Z");
    private static final UsagePeriod SEPTEMBER_PERIOD = UsagePeriod.utcMonthContaining(SEPTEMBER);

    @Autowired
    private JdbcUsageAccountingStore store;

    @Autowired
    private TenantOwnershipStore ownershipStore;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM tenant_usage_events").update();
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
        jdbc.sql("DELETE FROM tenant_repositories").update();
        jdbc.sql("DELETE FROM github_installations").update();
        jdbc.sql("DELETE FROM tenants").update();
    }

    @Test
    void reservationConsumptionIsIdempotentAndPersistsOnlySafeOptionalMetadata() {
        TenantContext tenant = ownershipStore.provision(101, 201, SEPTEMBER);
        UUID knownJob = createJob(tenant);
        UUID unknownJob = createJob(tenant);
        QuotaPolicy policy = new QuotaPolicy(3);

        assertThat(store.reserve(tenant, knownJob, SEPTEMBER_PERIOD, policy, SEPTEMBER).outcome())
                .isEqualTo(UsageReservationResult.Outcome.ACQUIRED);
        assertThat(store.reserve(tenant, knownJob, SEPTEMBER_PERIOD, policy, SEPTEMBER).outcome())
                .isEqualTo(UsageReservationResult.Outcome.EXISTING_RESERVED);
        UsageMeasurement known = new UsageMeasurement(Optional.of("openai"), Optional.of("model-1"),
                OptionalLong.of(100), OptionalLong.of(20), OptionalLong.of(40),
                OptionalLong.of(10), OptionalLong.of(140));
        store.consume(tenant, knownJob, known, SEPTEMBER.plusSeconds(1));
        store.consume(tenant, knownJob, known, SEPTEMBER.plusSeconds(2));

        store.reserve(tenant, unknownJob, SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(3));
        store.consume(tenant, unknownJob, new UsageMeasurement(Optional.empty(), Optional.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty()), SEPTEMBER.plusSeconds(4));

        assertThat(store.findByTenantAndReviewJobId(tenant.tenantId(), knownJob)).get()
                .extracting(event -> event.status()).isEqualTo(UsageStatus.CONSUMED);
        var summary = store.summarize(tenant.tenantId(), SEPTEMBER_PERIOD);
        assertThat(summary.reviewAnalyses()).isEqualTo(2);
        assertThat(summary.inputTokens().knownValue()).hasValue(100);
        assertThat(summary.inputTokens().complete()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_usage_events WHERE review_job_id=:jobId")
                .param("jobId", knownJob).query(Long.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> directUsageInsert(tenant, knownJob, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name='tenant_usage_events'
                  AND column_name IN ('prompt','source','patch','raw_output','findings')
                """).query(Long.class).single()).isZero();
    }

    @Test
    void quotaUsesReservedAndConsumedRecordsAndRollsOverAtUtcMonthBoundary() {
        TenantContext tenant = ownershipStore.provision(102, 202, SEPTEMBER);
        QuotaPolicy policy = new QuotaPolicy(3);
        UUID first = createJob(tenant);
        UUID second = createJob(tenant);
        UUID third = createJob(tenant);
        UUID denied = createJob(tenant);
        store.reserve(tenant, first, SEPTEMBER_PERIOD, policy, SEPTEMBER);
        store.consume(tenant, first, unavailable(), SEPTEMBER.plusSeconds(1));
        store.reserve(tenant, second, SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(2));

        assertThat(store.quota(tenant.tenantId(), SEPTEMBER_PERIOD, policy).used()).isEqualTo(2);
        assertThat(store.quota(tenant.tenantId(), SEPTEMBER_PERIOD, policy).remaining()).isEqualTo(1);
        assertThat(store.reserve(tenant, third, SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(3)).outcome())
                .isEqualTo(UsageReservationResult.Outcome.ACQUIRED);
        assertThat(store.reserve(tenant, denied, SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(4)).outcome())
                .isEqualTo(UsageReservationResult.Outcome.QUOTA_EXCEEDED);

        UsagePeriod october = UsagePeriod.utcMonthContaining(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(store.quota(tenant.tenantId(), october, policy).used()).isZero();
        assertThat(store.reserve(tenant, denied, october, policy, october.start()).outcome())
                .isEqualTo(UsageReservationResult.Outcome.ACQUIRED);
    }

    @Test
    void concurrentDistinctJobsCannotOvershootLastQuotaUnit() throws Exception {
        TenantContext tenant = ownershipStore.provision(103, 203, SEPTEMBER);
        QuotaPolicy policy = new QuotaPolicy(5);
        for (int index = 0; index < 4; index++) {
            store.reserve(tenant, createJob(tenant), SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(index));
        }
        List<UUID> candidates = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> createJob(tenant)).toList();
        CountDownLatch ready = new CountDownLatch(candidates.size());
        CountDownLatch go = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(candidates.size())) {
            var futures = candidates.stream().map(jobId -> executor.submit(() -> {
                ready.countDown();
                go.await();
                return store.reserve(tenant, jobId, SEPTEMBER_PERIOD, policy, SEPTEMBER).outcome();
            })).toList();
            ready.await();
            go.countDown();
            long acquired = 0;
            for (var future : futures) {
                if (future.get() == UsageReservationResult.Outcome.ACQUIRED) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        }
        assertThat(store.quota(tenant.tenantId(), SEPTEMBER_PERIOD, policy).used()).isEqualTo(5);
    }

    @Test
    void concurrentRetriesForOneJobConvergeOnOneReservation() throws Exception {
        TenantContext tenant = ownershipStore.provision(104, 204, SEPTEMBER);
        UUID jobId = createJob(tenant);
        int callers = 10;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(callers)) {
            var futures = java.util.stream.IntStream.range(0, callers).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                go.await();
                return store.reserve(tenant, jobId, SEPTEMBER_PERIOD, new QuotaPolicy(5), SEPTEMBER).outcome();
            })).toList();
            ready.await();
            go.countDown();
            assertThat(futures).filteredOn(future -> {
                try {
                    return future.get() == UsageReservationResult.Outcome.ACQUIRED;
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).hasSize(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM tenant_usage_events").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void crossTenantWritesAndNegativeTokenCountsFailAtServiceAndDatabaseBoundaries() {
        TenantContext tenantA = ownershipStore.provision(105, 205, SEPTEMBER);
        TenantContext tenantAOtherRepository = ownershipStore.provision(105, 215, SEPTEMBER);
        TenantContext tenantB = ownershipStore.provision(106, 206, SEPTEMBER);
        UUID jobA = createJob(tenantA);
        UsageAccountingService service = new UsageAccountingService(store, new QuotaPolicy(5),
                Clock.fixed(SEPTEMBER, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.reserve(tenantB, jobA))
                .isInstanceOfSatisfying(UsageAccountingException.class,
                        exception -> assertThat(exception.error()).isEqualTo(UsageAccountingError.USAGE_TENANT_MISMATCH))
                .hasMessageNotContaining(tenantA.tenantId().toString());
        store.reserve(tenantA, jobA, SEPTEMBER_PERIOD, new QuotaPolicy(5), SEPTEMBER);
        ReviewAnalysisMetadata metadata = new ReviewAnalysisMetadata("openai", "model-1",
                AiModelTier.BALANCED, AiTokenUsage.unavailable(), Duration.ZERO, 1);
        assertThatThrownBy(() -> service.consume(tenantB, jobA, metadata))
                .isInstanceOfSatisfying(UsageAccountingException.class,
                        exception -> assertThat(exception.error()).isEqualTo(UsageAccountingError.USAGE_TENANT_MISMATCH))
                .hasMessageNotContaining(tenantA.tenantId().toString());
        service.consume(tenantA, jobA, metadata);
        assertThat(tenantAOtherRepository.tenantId()).isEqualTo(tenantA.tenantId());
        assertThatThrownBy(() -> service.consume(tenantAOtherRepository, jobA, metadata))
                .isInstanceOfSatisfying(UsageAccountingException.class,
                        exception -> assertThat(exception.error()).isEqualTo(UsageAccountingError.USAGE_TENANT_MISMATCH));
        assertThat(store.findByTenantAndReviewJobId(tenantB.tenantId(), jobA)).isEmpty();
        assertThat(store.summarize(tenantB.tenantId(), SEPTEMBER_PERIOD).reviewAnalyses()).isZero();
        assertThat(store.quota(tenantB.tenantId(), SEPTEMBER_PERIOD, new QuotaPolicy(5)).used()).isZero();
        assertThatThrownBy(() -> directUsageInsert(tenantA, jobA, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void releasedReservationRemainsAuditableAndNoLongerConsumesQuota() {
        TenantContext tenant = ownershipStore.provision(107, 207, SEPTEMBER);
        UUID jobId = createJob(tenant);
        QuotaPolicy policy = new QuotaPolicy(1);
        store.reserve(tenant, jobId, SEPTEMBER_PERIOD, policy, SEPTEMBER);

        assertThat(store.release(tenant, jobId, SEPTEMBER.plusSeconds(1))).isTrue();
        assertThat(store.quota(tenant.tenantId(), SEPTEMBER_PERIOD, policy).used()).isZero();
        assertThat(store.reserve(tenant, jobId, SEPTEMBER_PERIOD, policy, SEPTEMBER.plusSeconds(2)).outcome())
                .isEqualTo(UsageReservationResult.Outcome.EXISTING_RELEASED);
        assertThat(store.findByTenantAndReviewJobId(tenant.tenantId(), jobId)).get()
                .extracting(event -> event.status()).isEqualTo(UsageStatus.RELEASED);
    }

    private UUID createJob(TenantContext tenant) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = SEPTEMBER.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at,
                   tenant_id, tenant_repository_id, created_at, updated_at)
                VALUES (:id, 'READY', 0, 3, :now, :tenantId, :tenantRepositoryId, :now, :now)
                """).param("id", id).param("now", now).param("tenantId", tenant.tenantId())
                .param("tenantRepositoryId", tenant.repositoryId()).update();
        return id;
    }

    private UsageMeasurement unavailable() {
        return new UsageMeasurement(Optional.empty(), Optional.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());
    }

    private void directUsageInsert(TenantContext tenant, UUID jobId, long inputTokens) {
        jdbc.sql("""
                INSERT INTO tenant_usage_events
                  (id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                   occurred_at, consumed_at, input_tokens, created_at, updated_at)
                VALUES (:id, :tenantId, :tenantRepositoryId, :reviewJobId, 'REVIEW_ANALYSIS', 'CONSUMED',
                        :now, :now, :inputTokens, :now, :now)
                """).param("id", UUID.randomUUID()).param("tenantId", tenant.tenantId())
                .param("tenantRepositoryId", tenant.repositoryId()).param("reviewJobId", jobId)
                .param("now", SEPTEMBER.atOffset(ZoneOffset.UTC)).param("inputTokens", inputTokens).update();
    }
}
