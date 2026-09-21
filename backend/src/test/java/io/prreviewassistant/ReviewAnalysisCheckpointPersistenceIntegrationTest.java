package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.prreviewassistant.review.analysis.JdbcReviewAnalysisCheckpointStore;
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointError;
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointException;
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointResult;
import io.prreviewassistant.review.analysis.ReviewCandidateAnalysis;
import io.prreviewassistant.review.analysis.ReviewFinding;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.analysis.ReviewSeverity;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.tenant.TenantOwnershipStore;
import io.prreviewassistant.usage.JdbcUsageAccountingStore;
import io.prreviewassistant.usage.QuotaPolicy;
import io.prreviewassistant.usage.UsageMeasurement;
import io.prreviewassistant.usage.UsagePeriod;
import io.prreviewassistant.usage.UsageStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=false",
        "REVIEW_PUBLICATION_ENABLED=false",
        "DB_JDBC_URL=jdbc:postgresql://unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1",
        "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=test-secret-that-is-long-enough"
})
@Import(PostgreSqlTestConfiguration.class)
class ReviewAnalysisCheckpointPersistenceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
    private static final ReviewTarget TARGET = new ReviewTarget(701, 801, 42, "a".repeat(40));

    @Autowired
    private JdbcReviewAnalysisCheckpointStore checkpoints;

    @Autowired
    private JdbcUsageAccountingStore usage;

    @Autowired
    private TenantOwnershipStore ownership;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM review_analysis_checkpoints").update();
        jdbc.sql("DELETE FROM tenant_usage_events").update();
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
        jdbc.sql("DELETE FROM tenant_repositories").update();
        jdbc.sql("DELETE FROM github_installations").update();
        jdbc.sql("DELETE FROM tenants").update();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    @Test
    void checkpointAndUsageConsumptionCommitAtomically() {
        TenantContext tenant = ownership.provision(TARGET.installationId(), TARGET.repositoryId(), NOW);
        UUID jobId = createJob(tenant, TARGET);
        reserve(tenant, jobId);

        assertThat(checkpoints.createAndConsume(tenant, jobId, candidates(), measurement(), NOW))
                .isEqualTo(ReviewAnalysisCheckpointResult.CREATED);

        assertThat(checkpoints.find(tenant, jobId)).contains(candidates());
        assertThat(usage.findByTenantAndReviewJobId(tenant.tenantId(), jobId)).get()
                .extracting(event -> event.status()).isEqualTo(UsageStatus.CONSUMED);
    }

    @Test
    void missingReservationRollsBackCheckpointInsert() {
        TenantContext tenant = ownership.provision(TARGET.installationId(), TARGET.repositoryId(), NOW);
        UUID jobId = createJob(tenant, TARGET);

        assertThatThrownBy(() -> checkpoints.createAndConsume(tenant, jobId, candidates(), measurement(), NOW))
                .isInstanceOfSatisfying(ReviewAnalysisCheckpointException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(ReviewAnalysisCheckpointError.INCONSISTENT_STATE));

        assertThat(checkpointCount(jobId)).isZero();
        assertThat(usage.findByTenantAndReviewJobId(tenant.tenantId(), jobId)).isEmpty();
    }

    @Test
    void concurrentCheckpointAttemptsConvergeOnOneCheckpointAndConsumption() throws Exception {
        TenantContext tenant = ownership.provision(TARGET.installationId(), TARGET.repositoryId(), NOW);
        UUID jobId = createJob(tenant, TARGET);
        reserve(tenant, jobId);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                go.await();
                return checkpoints.createAndConsume(tenant, jobId, candidates(), measurement(), NOW);
            })).toList();
            ready.await();
            go.countDown();
            assertThat(futures).extracting(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).containsExactlyInAnyOrder(
                    ReviewAnalysisCheckpointResult.CREATED,
                    ReviewAnalysisCheckpointResult.EXISTING);
        }

        assertThat(checkpointCount(jobId)).isEqualTo(1);
        assertThat(usage.findByTenantAndReviewJobId(tenant.tenantId(), jobId)).get()
                .extracting(event -> event.status()).isEqualTo(UsageStatus.CONSUMED);
    }

    @Test
    void tenantAndReviewTargetConsistencyFailClosed() {
        TenantContext tenantA = ownership.provision(TARGET.installationId(), TARGET.repositoryId(), NOW);
        TenantContext tenantB = ownership.provision(702, 802, NOW);
        UUID jobId = createJob(tenantA, TARGET);
        reserve(tenantA, jobId);

        assertThat(checkpoints.find(tenantB, jobId)).isEmpty();
        assertThatThrownBy(() -> checkpoints.createAndConsume(
                tenantB, jobId, candidates(), measurement(), NOW))
                .isInstanceOf(ReviewAnalysisCheckpointException.class);
        ReviewCandidateAnalysis wrongTarget = new ReviewCandidateAnalysis(
                new ReviewTarget(TARGET.installationId(), TARGET.repositoryId(), 43, TARGET.headSha()), List.of());
        assertThatThrownBy(() -> checkpoints.createAndConsume(
                tenantA, jobId, wrongTarget, measurement(), NOW))
                .isInstanceOf(ReviewAnalysisCheckpointException.class);
        assertThat(checkpointCount(jobId)).isZero();
    }

    @Test
    void malformedAndOversizedPayloadsAreRejectedWithoutContentExposure() {
        TenantContext tenant = ownership.provision(TARGET.installationId(), TARGET.repositoryId(), NOW);
        UUID jobId = createJob(tenant, TARGET);
        reserve(tenant, jobId);
        checkpoints.createAndConsume(tenant, jobId, candidates(), measurement(), NOW);
        jdbc.sql("UPDATE review_analysis_checkpoints SET findings_payload='{}' WHERE review_job_id=:jobId")
                .param("jobId", jobId).update();

        assertThatThrownBy(() -> checkpoints.find(tenant, jobId))
                .isInstanceOf(ReviewAnalysisCheckpointException.class)
                .hasMessage("Review analysis checkpoint failed: INCONSISTENT_STATE")
                .hasMessageNotContaining("title")
                .hasMessageNotContaining("evidence")
                .hasMessageNotContaining("secret");
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE review_analysis_checkpoints SET findings_payload=:payload
                WHERE review_job_id=:jobId
                """).param("payload", "x".repeat(262_145)).param("jobId", jobId).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void populatedV9SchemaUpgradesAdditivelyToV10WithoutFabricatingCheckpoint() throws Exception {
        String schema = "checkpoint_upgrade_v9";
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("9").load().migrate();
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                statement.execute("""
                        INSERT INTO tenants (id, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000901', TIMESTAMPTZ '2026-09-21 12:00:00Z',
                                TIMESTAMPTZ '2026-09-21 12:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO github_installations
                          (id, tenant_id, github_installation_id, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000902',
                                '00000000-0000-0000-0000-000000000901', 9701,
                                TIMESTAMPTZ '2026-09-21 12:00:00Z', TIMESTAMPTZ '2026-09-21 12:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO tenant_repositories
                          (id, tenant_id, installation_id, github_repository_id, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000903',
                                '00000000-0000-0000-0000-000000000901',
                                '00000000-0000-0000-0000-000000000902', 9801,
                                TIMESTAMPTZ '2026-09-21 12:00:00Z', TIMESTAMPTZ '2026-09-21 12:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO review_jobs
                          (id, status, attempts, max_attempts, completed_at, tenant_id, tenant_repository_id,
                           github_installation_id, github_repository_id, github_pull_request_number,
                           github_head_sha, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000904', 'COMPLETED', 1, 3,
                                TIMESTAMPTZ '2026-09-21 12:00:00Z',
                                '00000000-0000-0000-0000-000000000901',
                                '00000000-0000-0000-0000-000000000903', 9701, 9801, 42,
                                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                                TIMESTAMPTZ '2026-09-21 12:00:00Z', TIMESTAMPTZ '2026-09-21 12:00:00Z')
                        """);
                statement.execute("""
                        INSERT INTO tenant_usage_events
                          (id, tenant_id, tenant_repository_id, review_job_id, usage_type, status,
                           occurred_at, consumed_at, created_at, updated_at)
                        VALUES ('00000000-0000-0000-0000-000000000905',
                                '00000000-0000-0000-0000-000000000901',
                                '00000000-0000-0000-0000-000000000903',
                                '00000000-0000-0000-0000-000000000904',
                                'REVIEW_ANALYSIS', 'CONSUMED',
                                TIMESTAMPTZ '2026-09-21 12:00:00Z', TIMESTAMPTZ '2026-09-21 12:00:00Z',
                                TIMESTAMPTZ '2026-09-21 12:00:00Z', TIMESTAMPTZ '2026-09-21 12:00:00Z')
                        """);
                statement.execute("SET search_path TO public");
            }

            var result = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load().migrate();

            assertThat(result.targetSchemaVersion).isEqualTo("10");
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                try (var usageRows = statement.executeQuery("SELECT count(*) FROM " + schema
                        + ".tenant_usage_events WHERE status='CONSUMED'")) {
                    assertThat(usageRows.next()).isTrue();
                    assertThat(usageRows.getLong(1)).isOne();
                }
                try (var checkpointRows = statement.executeQuery(
                        "SELECT count(*) FROM " + schema + ".review_analysis_checkpoints")) {
                    assertThat(checkpointRows.next()).isTrue();
                    assertThat(checkpointRows.getLong(1)).isZero();
                }
            }
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private void reserve(TenantContext tenant, UUID jobId) {
        usage.reserve(tenant, jobId, UsagePeriod.utcMonthContaining(NOW), new QuotaPolicy(10), NOW);
    }

    private long checkpointCount(UUID jobId) {
        return jdbc.sql("SELECT count(*) FROM review_analysis_checkpoints WHERE review_job_id=:jobId")
                .param("jobId", jobId).query(Long.class).single();
    }

    private UUID createJob(TenantContext tenant, ReviewTarget target) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = NOW.atOffset(java.time.ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO review_jobs
                  (id, status, attempts, max_attempts, next_attempt_at,
                   tenant_id, tenant_repository_id,
                   github_installation_id, github_repository_id,
                   github_pull_request_number, github_head_sha,
                   created_at, updated_at)
                VALUES (:id, 'READY', 0, 3, :now, :tenantId, :tenantRepositoryId,
                        :installationId, :repositoryId, :pullRequestNumber, :headSha, :now, :now)
                """).param("id", id).param("now", now)
                .param("tenantId", tenant.tenantId()).param("tenantRepositoryId", tenant.repositoryId())
                .param("installationId", target.installationId()).param("repositoryId", target.repositoryId())
                .param("pullRequestNumber", target.pullRequestNumber()).param("headSha", target.headSha()).update();
        return id;
    }

    private ReviewCandidateAnalysis candidates() {
        return new ReviewCandidateAnalysis(TARGET, List.of(new ReviewFinding(
                "b".repeat(64), ReviewFindingCategory.RELIABILITY, ReviewSeverity.HIGH, 95,
                "src/main/App.java", 10, 10, "Lost durable update",
                "The transaction commits usage before durable output.",
                "A successful review can be lost after charging quota.",
                "Persist the validated result in the same transaction.", null)));
    }

    private UsageMeasurement measurement() {
        return new UsageMeasurement(Optional.of("openai"), Optional.of("model-1"),
                OptionalLong.of(100), OptionalLong.of(10), OptionalLong.of(20),
                OptionalLong.of(5), OptionalLong.of(120));
    }
}
