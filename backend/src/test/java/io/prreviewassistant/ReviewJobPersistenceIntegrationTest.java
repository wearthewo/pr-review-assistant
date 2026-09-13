package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.prreviewassistant.review.job.ClaimedReviewJob;
import io.prreviewassistant.review.job.ReviewJob;
import io.prreviewassistant.review.job.ReviewJobCreationResult;
import io.prreviewassistant.review.job.ReviewJobErrorCode;
import io.prreviewassistant.review.job.ReviewJobExecutionResult;
import io.prreviewassistant.review.job.ReviewJobHandler;
import io.prreviewassistant.review.job.ReviewJobRetryPolicy;
import io.prreviewassistant.review.job.ReviewJobStatus;
import io.prreviewassistant.review.job.ReviewJobStore;
import io.prreviewassistant.review.job.ReviewJobWorker;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.job.ReviewWorkerProperties;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
        "GITHUB_APP_ID=test-app-id",
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem",
        "GITHUB_WEBHOOK_SECRET=test-webhook-secret-with-entropy",
        "REVIEW_WORKER_ENABLED=false"
})
@Import(PostgreSqlTestConfiguration.class)
class ReviewJobPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final ReviewJobErrorCode RETRYABLE = new ReviewJobErrorCode("TEMPORARY_FAILURE");
    private static final ReviewJobErrorCode TERMINAL = new ReviewJobErrorCode("INVALID_WORK");

    @Autowired
    private ReviewJobStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ReviewJobHandler reviewJobHandler;

    @Autowired
    private TenantOwnershipStore tenantOwnershipStore;

    @BeforeEach
    void clearJobs() {
        jdbcClient.sql("DELETE FROM review_jobs").update();
    }

    @Test
    void createsReadyJobWithExplicitInitialState() {
        ReviewJob created = store.create(3, NOW);
        StoredJob job = job(created.id());

        assertThat(created.status()).isEqualTo(ReviewJobStatus.READY);
        assertThat(job.status()).isEqualTo("READY");
        assertThat(job.attempts()).isZero();
        assertThat(job.maxAttempts()).isEqualTo(3);
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
        assertThat(job.createdAt()).isEqualTo(NOW);
        assertThat(job.updatedAt()).isEqualTo(NOW);
        assertThat(job.claimToken()).isNull();
    }

    @Test
    void createsAndClaimsARevisionSpecificJobWithAllIdentityFields() {
        ReviewTarget target = new ReviewTarget(101, 202, 42, "a".repeat(40));

        assertThat(createForTarget(target, 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        ClaimedReviewJob claim = store.claimDue(NOW, LEASE, 1).getFirst();
        assertThat(claim.reviewTarget()).isEqualTo(target);
        assertThat(claim.tenantContext())
                .isEqualTo(tenantOwnershipStore.resolve(101, 202).orElseThrow());

        StoredTarget stored = jdbcClient.sql("""
                        SELECT github_installation_id, github_repository_id,
                               github_pull_request_number, github_head_sha
                        FROM review_jobs
                        """)
                .query((resultSet, rowNumber) -> new StoredTarget(
                        resultSet.getLong("github_installation_id"),
                        resultSet.getLong("github_repository_id"),
                        resultSet.getInt("github_pull_request_number"),
                        resultSet.getString("github_head_sha")))
                .single();
        assertThat(stored).isEqualTo(new StoredTarget(101, 202, 42, "a".repeat(40)));
    }

    @Test
    void targetCreationUsesDatabaseConflictHandlingAndEachIdentityDimension() {
        ReviewTarget target = new ReviewTarget(101, 202, 42, "a".repeat(40));

        assertThat(createForTarget(target, 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        assertThat(createForTarget(target, 3, NOW))
                .isEqualTo(ReviewJobCreationResult.ALREADY_EXISTS);
        assertThat(createForTarget(new ReviewTarget(102, 204, 42, "a".repeat(40)), 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        assertThat(createForTarget(new ReviewTarget(101, 203, 42, "a".repeat(40)), 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        assertThat(createForTarget(new ReviewTarget(101, 202, 43, "a".repeat(40)), 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        assertThat(createForTarget(new ReviewTarget(101, 202, 42, "b".repeat(40)), 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);

        assertThat(jdbcClient.sql("SELECT count(*) FROM review_jobs").query(Long.class).single()).isEqualTo(5);
    }

    @Test
    void databaseConstraintDirectlyRejectsDuplicateReviewTargets() {
        String sql = """
                INSERT INTO review_jobs
                    (id, status, attempts, max_attempts, next_attempt_at,
                     github_installation_id, github_repository_id,
                     github_pull_request_number, github_head_sha, created_at, updated_at)
                VALUES (:id, 'READY', 0, 3, :now, 101, 202, 42, :sha, :now, :now)
                """;
        jdbcClient.sql(sql)
                .param("id", UUID.randomUUID()).param("now", offset(NOW)).param("sha", "a".repeat(40)).update();

        assertThatThrownBy(() -> jdbcClient.sql(sql)
                .param("id", UUID.randomUUID()).param("now", offset(NOW)).param("sha", "a".repeat(40)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseConstraintRejectsPartialOrInvalidReviewTargets() {
        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO review_jobs
                            (id, status, attempts, max_attempts, next_attempt_at,
                             github_installation_id, created_at, updated_at)
                        VALUES (:id, 'READY', 0, 3, :now, 101, :now, :now)
                        """)
                .param("id", UUID.randomUUID()).param("now", offset(NOW)).update())
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO review_jobs
                            (id, status, attempts, max_attempts, next_attempt_at,
                             github_installation_id, github_repository_id,
                             github_pull_request_number, github_head_sha, created_at, updated_at)
                        VALUES (:id, 'READY', 0, 3, :now, 101, 202, 42, 'not-a-git-id', :now, :now)
                        """)
                .param("id", UUID.randomUUID()).param("now", offset(NOW)).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void migrationProvidesPollingIndexesAndStateConstraints() {
        List<String> indexes = jdbcClient.sql("""
                        SELECT indexname FROM pg_indexes
                        WHERE schemaname = 'public' AND tablename = 'review_jobs'
                        """)
                .query(String.class)
                .list();
        assertThat(indexes).contains("ix_review_jobs_ready_poll", "ix_review_jobs_expired_claims");

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO review_jobs
                            (id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at)
                        VALUES (:id, 'UNKNOWN', 0, 3, :now, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("now", offset(NOW))
                .update()).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcClient.sql("""
                        INSERT INTO review_jobs
                            (id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at)
                        VALUES (:id, 'READY', -1, 3, :now, :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("now", offset(NOW))
                .update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimsOnlyDueJobsAndSetsAttemptAndLeaseMetadata() {
        ReviewJob due = store.create(3, NOW.minusSeconds(1));
        ReviewJob future = store.create(3, NOW.plusSeconds(1));

        List<ClaimedReviewJob> claims = store.claimDue(NOW, LEASE, 10);

        assertThat(claims).extracting(ClaimedReviewJob::id).containsExactly(due.id());
        ClaimedReviewJob claim = claims.getFirst();
        assertThat(claim.attempt()).isOne();
        assertThat(claim.claimedAt()).isEqualTo(NOW);
        assertThat(claim.claimExpiresAt()).isEqualTo(NOW.plus(LEASE));
        assertThat(claim.claimToken()).isNotNull();
        assertThat(job(due.id()).status()).isEqualTo("PROCESSING");
        assertThat(job(due.id()).attempts()).isOne();
        assertThat(job(future.id()).status()).isEqualTo("READY");
    }

    @Test
    void claimOrderingIsDeterministicAndBatchIsBounded() {
        ReviewJob third = store.create(3, NOW.minusSeconds(1));
        ReviewJob first = store.create(3, NOW.minusSeconds(3));
        ReviewJob second = store.create(3, NOW.minusSeconds(2));

        List<ClaimedReviewJob> claims = store.claimDue(NOW, LEASE, 2);

        assertThat(claims).extracting(ClaimedReviewJob::id).containsExactly(first.id(), second.id());
        assertThat(job(third.id()).status()).isEqualTo("READY");
    }

    @Test
    void completedAndFailedJobsAreNeverClaimable() {
        ClaimedReviewJob completed = claim(store.create(3, NOW));
        ClaimedReviewJob failed = claim(store.create(3, NOW));
        assertThat(store.complete(completed.id(), completed.claimToken(), NOW.plusSeconds(1))).isTrue();
        assertThat(store.fail(failed.id(), failed.claimToken(), TERMINAL, NOW.plusSeconds(1))).isTrue();

        assertThat(store.claimDue(NOW.plus(Duration.ofDays(1)), LEASE, 10)).isEmpty();
        assertThat(job(completed.id()).completedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(job(failed.id()).failedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void nonExpiredClaimIsNotReclaimedAndExpiredClaimGetsNewOwner() {
        ClaimedReviewJob first = claim(store.create(3, NOW));

        assertThat(store.claimDue(first.claimExpiresAt().minusMillis(1), LEASE, 1)).isEmpty();
        ClaimedReviewJob second = store.claimDue(first.claimExpiresAt(), LEASE, 1).getFirst();

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
    }

    @Test
    void staleOwnerCannotCompleteAfterLeaseRecoveryButCurrentOwnerCan() {
        ClaimedReviewJob stale = claim(store.create(3, NOW));
        ClaimedReviewJob current = store.claimDue(stale.claimExpiresAt(), LEASE, 1).getFirst();

        assertThat(store.complete(stale.id(), stale.claimToken(), NOW.plus(LEASE).plusSeconds(1))).isFalse();
        assertThat(store.complete(current.id(), current.claimToken(), NOW.plus(LEASE).plusSeconds(1))).isTrue();
        assertThat(job(current.id()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void retryClearsOwnershipAndSchedulesNextAttempt() {
        ClaimedReviewJob claim = claim(store.create(3, NOW));
        Instant failedAt = NOW.plusSeconds(5);
        Instant nextAttempt = failedAt.plusSeconds(10);

        assertThat(store.retry(claim.id(), claim.claimToken(), nextAttempt, RETRYABLE, failedAt)).isTrue();
        StoredJob job = job(claim.id());
        assertThat(job.status()).isEqualTo("READY");
        assertThat(job.claimToken()).isNull();
        assertThat(job.claimedAt()).isNull();
        assertThat(job.claimExpiresAt()).isNull();
        assertThat(job.nextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(job.lastErrorCode()).isEqualTo(RETRYABLE.value());
        assertThat(store.claimDue(nextAttempt.minusMillis(1), LEASE, 1)).isEmpty();
        assertThat(store.claimDue(nextAttempt, LEASE, 1)).hasSize(1);
    }

    @Test
    void terminalAndMaxAttemptFailuresBecomeFailed() {
        ClaimedReviewJob terminal = claim(store.create(3, NOW));
        assertThat(store.fail(terminal.id(), terminal.claimToken(), TERMINAL, NOW.plusSeconds(1))).isTrue();
        assertThat(job(terminal.id()).status()).isEqualTo("FAILED");
        assertThat(job(terminal.id()).lastErrorCode()).isEqualTo(TERMINAL.value());

        ReviewJob oneAttempt = store.create(1, NOW.plusSeconds(2));
        ClaimedReviewJob exhausted = store.claimDue(NOW.plusSeconds(2), LEASE, 1).getFirst();
        assertThat(store.retry(exhausted.id(), exhausted.claimToken(), NOW.plusSeconds(3), RETRYABLE,
                NOW.plusSeconds(3))).isFalse();
        assertThat(store.claimDue(exhausted.claimExpiresAt(), LEASE, 1)).isEmpty();
        assertThat(job(oneAttempt.id()).status()).isEqualTo("FAILED");
        assertThat(job(oneAttempt.id()).lastErrorCode()).isEqualTo("LEASE_EXPIRED_MAX_ATTEMPTS");
    }

    @Test
    void workerExecutesHandlerOutsideClaimTransaction() {
        ReviewJob created = store.create(3, NOW);
        List<Boolean> transactionStates = new ArrayList<>();
        ReviewJobWorker worker = new ReviewJobWorker(
                store,
                claim -> {
                    transactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
                    assertThat(job(claim.id()).status()).isEqualTo("PROCESSING");
                    return ReviewJobExecutionResult.success();
                },
                new ReviewWorkerProperties(true, Duration.ofSeconds(5), 1, LEASE),
                new ReviewJobRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(worker.pollOnce()).isOne();
        assertThat(transactionStates).containsExactly(false);
        assertThat(job(created.id()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void lockedOldestJobIsSkippedInsteadOfBlockingAnotherWorker() throws Exception {
        ReviewJob oldest = store.create(3, NOW.minusSeconds(2));
        ReviewJob next = store.create(3, NOW.minusSeconds(1));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> locker = executor.submit(() -> transaction.executeWithoutResult(status -> {
                jdbcClient.sql("SELECT id FROM review_jobs WHERE id = :id FOR UPDATE")
                        .param("id", oldest.id())
                        .query(UUID.class)
                        .single();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<List<ClaimedReviewJob>> claimant =
                    executor.submit(() -> store.claimDue(NOW, LEASE, 2));
            assertThat(claimant.get(10, TimeUnit.SECONDS))
                    .extracting(ClaimedReviewJob::id)
                    .containsExactly(next.id());
            release.countDown();
            locker.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void concurrentWorkersSplitPoolWithoutDuplicateOwnership() throws Exception {
        int jobs = 18;
        for (int index = 0; index < jobs; index++) {
            store.create(3, NOW);
        }

        List<ClaimedReviewJob> claims = concurrentClaims(6, 3, NOW);

        assertThat(claims).hasSize(jobs);
        assertThat(claims).extracting(ClaimedReviewJob::id).doesNotHaveDuplicates();
        assertThat(claims).extracting(ClaimedReviewJob::claimToken).doesNotHaveDuplicates();
        assertThat(countStatus("PROCESSING")).isEqualTo(jobs);
    }

    @Test
    void oneJobClaimedByManyWorkersHasExactlyOneOwner() throws Exception {
        store.create(3, NOW);

        List<ClaimedReviewJob> claims = concurrentClaims(8, 1, NOW);

        assertThat(claims).hasSize(1);
        assertThat(countStatus("PROCESSING")).isOne();
    }

    @Test
    void expiredLeaseReclaimRaceProducesExactlyOneNewOwner() throws Exception {
        ClaimedReviewJob original = claim(store.create(3, NOW));

        List<ClaimedReviewJob> claims = concurrentClaims(8, 1, original.claimExpiresAt());

        assertThat(claims).hasSize(1);
        assertThat(claims.getFirst().claimToken()).isNotEqualTo(original.claimToken());
        assertThat(claims.getFirst().attempt()).isEqualTo(2);
    }

    @Test
    void concurrentCompletionsUseOwnershipGuardSafely() throws Exception {
        ClaimedReviewJob claim = claim(store.create(3, NOW));
        int callers = 8;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return store.complete(claim.id(), claim.claimToken(), NOW.plusSeconds(1));
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertThat(results).containsOnlyOnce(true);
        }

        assertThat(job(claim.id()).status()).isEqualTo("COMPLETED");
    }

    private ClaimedReviewJob claim(ReviewJob job) {
        return store.claimDue(job.nextAttemptAt(), LEASE, 1).getFirst();
    }

    private List<ClaimedReviewJob> concurrentClaims(int callers, int batchSize, Instant now) throws Exception {
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<ClaimedReviewJob>>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            for (int index = 0; index < callers; index++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return store.claimDue(now, LEASE, batchSize);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ClaimedReviewJob> claims = new ArrayList<>();
            for (Future<List<ClaimedReviewJob>> future : futures) {
                claims.addAll(future.get(20, TimeUnit.SECONDS));
            }
            return claims;
        }
    }

    private StoredJob job(UUID id) {
        return jdbcClient.sql("SELECT * FROM review_jobs WHERE id = :id")
                .param("id", id)
                .query((resultSet, rowNumber) -> new StoredJob(
                        resultSet.getString("status"),
                        resultSet.getInt("attempts"),
                        resultSet.getInt("max_attempts"),
                        instant(resultSet.getObject("next_attempt_at", OffsetDateTime.class)),
                        resultSet.getObject("claim_token", UUID.class),
                        instant(resultSet.getObject("claimed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("claim_expires_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("failed_at", OffsetDateTime.class)),
                        resultSet.getString("last_error_code"),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .single();
    }

    private long countStatus(String status) {
        return jdbcClient.sql("SELECT count(*) FROM review_jobs WHERE status = :status")
                .param("status", status)
                .query(Long.class)
                .single();
    }

    private Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test synchronization interrupted", exception);
        }
    }

    private record StoredJob(
            String status,
            int attempts,
            int maxAttempts,
            Instant nextAttemptAt,
            UUID claimToken,
            Instant claimedAt,
            Instant claimExpiresAt,
            Instant completedAt,
            Instant failedAt,
            String lastErrorCode,
            Instant createdAt,
            Instant updatedAt) {
    }

    @Test
    void productionRetrievalHandlerCannotSilentlyCompleteARealReviewJob() {
        ReviewTarget target = new ReviewTarget(101, 202, 42, "a".repeat(40));
        assertThat(createForTarget(target, 3, NOW))
                .isEqualTo(ReviewJobCreationResult.CREATED);
        ReviewJobWorker worker = new ReviewJobWorker(
                store,
                reviewJobHandler,
                new ReviewWorkerProperties(true, Duration.ofSeconds(5), 1, LEASE),
                new ReviewJobRetryPolicy(Duration.ofSeconds(10), Duration.ofMinutes(5)),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(worker.pollOnce()).isOne();
        StoredJob stored = jdbcClient.sql("SELECT * FROM review_jobs")
                .query((resultSet, rowNumber) -> new StoredJob(
                        resultSet.getString("status"), resultSet.getInt("attempts"),
                        resultSet.getInt("max_attempts"),
                        instant(resultSet.getObject("next_attempt_at", OffsetDateTime.class)),
                        resultSet.getObject("claim_token", UUID.class),
                        instant(resultSet.getObject("claimed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("claim_expires_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("completed_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("failed_at", OffsetDateTime.class)),
                        resultSet.getString("last_error_code"),
                        instant(resultSet.getObject("created_at", OffsetDateTime.class)),
                        instant(resultSet.getObject("updated_at", OffsetDateTime.class))))
                .single();
        assertThat(stored.status()).isEqualTo("FAILED");
        assertThat(stored.completedAt()).isNull();
        assertThat(stored.lastErrorCode()).isEqualTo("GITHUB_LOCAL_CONFIGURATION_INVALID");
    }

    private record StoredTarget(long installationId, long repositoryId, int pullRequestNumber, String headSha) {
    }

    private ReviewJobCreationResult createForTarget(ReviewTarget target, int maxAttempts, Instant now) {
        var context = tenantOwnershipStore.provision(
                target.installationId(), target.repositoryId(), now);
        return store.createForReviewTarget(context, target, maxAttempts, now);
    }
}
