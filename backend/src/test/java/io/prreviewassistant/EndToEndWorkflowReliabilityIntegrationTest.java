package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.github.client.GitHubPublishedReview;
import io.prreviewassistant.github.client.GitHubReviewPublisher;
import io.prreviewassistant.github.webhook.GitHubWebhookService;
import io.prreviewassistant.review.analysis.ReviewAnalysis;
import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import io.prreviewassistant.review.analysis.ReviewEngine;
import io.prreviewassistant.review.analysis.ReviewFinding;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.analysis.ReviewSeverity;
import io.prreviewassistant.review.config.EffectiveRepositoryReviewConfig;
import io.prreviewassistant.review.config.RepositoryConfigLoadResult;
import io.prreviewassistant.review.config.RepositoryConfigLoader;
import io.prreviewassistant.review.config.RepositoryConfigStatus;
import io.prreviewassistant.review.context.ContextBudgetUsage;
import io.prreviewassistant.review.context.ReviewContext;
import io.prreviewassistant.review.context.ReviewContextBuildResult;
import io.prreviewassistant.review.context.ReviewContextBuilder;
import io.prreviewassistant.review.job.ReviewJobWorker;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.publication.PublicationWorker;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.ChangedFileStatus;
import io.prreviewassistant.review.retrieval.PatchAvailability;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "REVIEW_WORKER_ENABLED=true",
        "REVIEW_WORKER_BATCH_SIZE=10",
        "REVIEW_PUBLICATION_ENABLED=true",
        "REVIEW_PUBLICATION_BATCH_SIZE=10",
        "REVIEW_USAGE_MONTHLY_LIMIT=10",
        "DB_JDBC_URL=jdbc:postgresql://unused",
        "DB_USERNAME=unused",
        "DB_PASSWORD=unused",
        "GITHUB_APP_ID=1",
        "GITHUB_PRIVATE_KEY_PATH=unused",
        "GITHUB_WEBHOOK_SECRET=e2e-reliability-secret-with-entropy"
})
@Import(PostgreSqlTestConfiguration.class)
class EndToEndWorkflowReliabilityIntegrationTest {
    private static final String SECRET = "e2e-reliability-secret-with-entropy";
    private static final ReviewTarget TARGET = new ReviewTarget(9001, 9002, 42, "a".repeat(40));

    @Autowired GitHubWebhookService webhooks;
    @Autowired ReviewJobWorker reviewWorker;
    @Autowired PublicationWorker publicationWorker;
    @Autowired JdbcClient jdbc;

    @MockitoBean PullRequestLoader loader;
    @MockitoBean ReviewContextBuilder contextBuilder;
    @MockitoBean RepositoryConfigLoader configLoader;
    @MockitoBean ReviewEngine reviewEngine;
    @MockitoBean GitHubReviewPublisher publisher;

    private PullRequestSnapshot snapshot;
    private ReviewContext context;
    private EffectiveRepositoryReviewConfig config;

    @BeforeEach
    void prepare() {
        clearDatabase();

        ChangedFile changed = new ChangedFile("src/main/App.java", null, ChangedFileStatus.MODIFIED,
                1, 1, 2, PatchAvailability.AVAILABLE,
                "@@ -1 +1 @@\n-returnEarly();\n+persistTransaction();\n");
        snapshot = new PullRequestSnapshot(TARGET.installationId(), TARGET.repositoryId(),
                "trusted", "repository", TARGET.pullRequestNumber(), TARGET.headSha(),
                "b".repeat(40), false, List.of(changed));
        context = new ReviewContext(TARGET, snapshot, List.of(),
                new ContextBudgetUsage(1, 0, 0, 0, 0, 0, false));
        config = EffectiveRepositoryReviewConfig.defaults();
        when(loader.load(TARGET)).thenReturn(PullRequestLoadResult.ready(snapshot));
        when(configLoader.load(snapshot)).thenReturn(
                new RepositoryConfigLoadResult(RepositoryConfigStatus.VALID, config));
        when(contextBuilder.build(snapshot, config)).thenReturn(ReviewContextBuildResult.ready(context));
    }

    @AfterEach
    void cleanup() {
        clearDatabase();
    }

    private void clearDatabase() {
        jdbc.sql("DELETE FROM review_analysis_checkpoints").update();
        jdbc.sql("DELETE FROM tenant_usage_events").update();
        jdbc.sql("DELETE FROM publication_jobs").update();
        jdbc.sql("DELETE FROM review_publications").update();
        jdbc.sql("DELETE FROM review_jobs").update();
        jdbc.sql("DELETE FROM github_webhook_deliveries").update();
        jdbc.sql("DELETE FROM tenant_repositories").update();
        jdbc.sql("DELETE FROM github_installations").update();
        jdbc.sql("DELETE FROM tenants").update();
    }

    @Test
    void signedWebhookCompletesOneChargedAnalysisAndOneDurablePublication() {
        ReviewAnalysis analysis = analysis(List.of(validFinding()));
        when(reviewEngine.analyze(context, config.enabledCategories())).thenReturn(analysis);
        when(publisher.create(anyLong(), anyString(), anyString(), anyInt(), anyString(), any(), anyInt()))
                .thenReturn(new GitHubPublishedReview(7001, Instant.parse("2026-09-21T12:00:00Z")));

        ingest("golden-delivery", TARGET);
        assertThat(reviewWorker.pollOnce()).isOne();

        assertThat(count("github_webhook_deliveries")).isOne();
        assertThat(count("review_jobs")).isOne();
        assertThat(count("review_analysis_checkpoints")).isOne();
        assertThat(countWhere("tenant_usage_events", "status='CONSUMED'")).isOne();
        assertThat(countWhere("review_jobs", "status='COMPLETED'")).isOne();
        assertThat(countWhere("review_publications", "status='PENDING'")).isOne();
        assertThat(countWhere("publication_jobs", "status='READY'")).isOne();

        assertThat(publicationWorker.pollOnce()).isOne();

        assertThat(countWhere("review_publications", "status='PUBLISHED'")).isOne();
        assertThat(countWhere("publication_jobs", "status='COMPLETED'")).isOne();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM review_analysis_checkpoints AS checkpoint
                JOIN review_jobs AS job ON job.id=checkpoint.review_job_id
                  AND job.tenant_id=checkpoint.tenant_id
                  AND job.tenant_repository_id=checkpoint.tenant_repository_id
                JOIN tenant_usage_events AS usage ON usage.review_job_id=job.id
                  AND usage.tenant_id=job.tenant_id
                  AND usage.tenant_repository_id=job.tenant_repository_id
                JOIN review_publications AS publication ON publication.analysis_job_id=job.id
                  AND publication.tenant_id=job.tenant_id
                  AND publication.tenant_repository_id=job.tenant_repository_id
                WHERE job.github_installation_id=:installationId
                  AND job.github_repository_id=:repositoryId
                  AND job.github_pull_request_number=:pullRequestNumber
                  AND job.github_head_sha=:headSha
                """).param("installationId", TARGET.installationId())
                .param("repositoryId", TARGET.repositoryId())
                .param("pullRequestNumber", TARGET.pullRequestNumber())
                .param("headSha", TARGET.headSha()).query(Long.class).single()).isOne();
        verify(reviewEngine).analyze(context, config.enabledCategories());
        verify(publisher).create(anyLong(), anyString(), anyString(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    void duplicateDeliveryAndSameTargetRemainOneAnalysisAndOnePublication() {
        when(reviewEngine.analyze(context, config.enabledCategories()))
                .thenReturn(analysis(List.of(validFinding())));
        when(publisher.create(anyLong(), anyString(), anyString(), anyInt(), anyString(), any(), anyInt()))
                .thenReturn(new GitHubPublishedReview(7002, Instant.parse("2026-09-21T12:00:00Z")));

        ingest("duplicate", TARGET);
        ingest("duplicate", TARGET);
        ingest("different-delivery", TARGET);
        reviewWorker.pollOnce();
        publicationWorker.pollOnce();

        assertThat(count("github_webhook_deliveries")).isEqualTo(2);
        assertThat(count("review_jobs")).isOne();
        assertThat(count("review_analysis_checkpoints")).isOne();
        assertThat(count("tenant_usage_events")).isOne();
        assertThat(count("review_publications")).isOne();
        verify(reviewEngine).analyze(context, config.enabledCategories());
        verify(publisher).create(anyLong(), anyString(), anyString(), anyInt(), anyString(), any(), anyInt());
    }

    @Test
    void zeroCandidatesCheckpointAndCompleteWithoutPublication() {
        when(reviewEngine.analyze(context, config.enabledCategories())).thenReturn(analysis(List.of()));

        ingest("zero-findings", TARGET);
        assertThat(reviewWorker.pollOnce()).isOne();

        assertThat(count("review_analysis_checkpoints")).isOne();
        assertThat(countWhere("review_analysis_checkpoints", "finding_count=0")).isOne();
        assertThat(countWhere("tenant_usage_events", "status='CONSUMED'")).isOne();
        assertThat(countWhere("review_jobs", "status='COMPLETED'")).isOne();
        assertThat(count("review_publications")).isZero();
        assertThat(count("publication_jobs")).isZero();
        verify(reviewEngine).analyze(context, config.enabledCategories());
        org.mockito.Mockito.verifyNoInteractions(publisher);
    }

    private ReviewAnalysis analysis(List<ReviewFinding> findings) {
        return new ReviewAnalysis(TARGET, findings, new ReviewAnalysisMetadata(
                "fake", "test-model", AiModelTier.BALANCED,
                new AiTokenUsage(java.util.OptionalLong.of(100), java.util.OptionalLong.of(10),
                        java.util.OptionalLong.of(20), java.util.OptionalLong.of(5),
                        java.util.OptionalLong.of(120)),
                Duration.ofSeconds(1), 1));
    }

    private ReviewFinding validFinding() {
        return new ReviewFinding("c".repeat(64), ReviewFindingCategory.TRANSACTIONAL_INTEGRITY,
                ReviewSeverity.HIGH, 97, "src/main/App.java", 1, 1,
                "persistTransaction commits after success is returned",
                "persistTransaction() returns before its database transaction is durably committed.",
                "A process failure can lose an update that callers already observed as successful.",
                "Commit the transaction before returning success to the caller.", null);
    }

    private void ingest(String deliveryId, ReviewTarget target) {
        String json = """
                {"action":"opened","installation":{"id":%d},"repository":{"id":%d},
                 "number":%d,"pull_request":{"head":{"sha":"%s"}}}
                """.formatted(target.installationId(), target.repositoryId(),
                target.pullRequestNumber(), target.headSha());
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        webhooks.ingest(body, sign(body), deliveryId, "pull_request");
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("test HMAC setup failed", exception);
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long countWhere(String table, String predicate) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + predicate)
                .query(Long.class).single();
    }
}
