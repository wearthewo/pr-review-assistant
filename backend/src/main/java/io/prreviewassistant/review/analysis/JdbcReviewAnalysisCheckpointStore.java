package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.usage.UsageMeasurement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReviewAnalysisCheckpointStore implements ReviewAnalysisCheckpointStore {
    private static final int PAYLOAD_VERSION = 1;

    private final JdbcClient jdbc;
    private final ReviewAnalysisCheckpointCodec codec;

    public JdbcReviewAnalysisCheckpointStore(JdbcClient jdbc, ReviewAnalysisCheckpointCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    @Override
    public Optional<ReviewCandidateAnalysis> find(TenantContext tenantContext, UUID reviewJobId) {
        requireIdentity(tenantContext, reviewJobId);
        try {
            Optional<Row> row = findRow(tenantContext, reviewJobId);
            if (row.isPresent() && !usageIsConsumed(tenantContext, reviewJobId)) {
                throw inconsistent();
            }
            return row.map(this::decode);
        } catch (ReviewAnalysisCheckpointException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw failed();
        }
    }

    @Override
    @Transactional
    public ReviewAnalysisCheckpointResult createAndConsume(
            TenantContext tenantContext,
            UUID reviewJobId,
            ReviewCandidateAnalysis analysis,
            UsageMeasurement measurement,
            Instant now) {
        requireIdentity(tenantContext, reviewJobId);
        if (analysis == null || measurement == null || now == null) {
            throw new IllegalArgumentException("checkpoint data is required");
        }
        String payload = codec.encode(analysis.findings());
        OffsetDateTime timestamp = now.atOffset(ZoneOffset.UTC);
        try {
            int inserted = jdbc.sql("""
                    INSERT INTO review_analysis_checkpoints
                      (id, tenant_id, tenant_repository_id, review_job_id, payload_version,
                       finding_count, findings_payload, created_at, updated_at)
                    SELECT :id, job.tenant_id, job.tenant_repository_id, job.id, :payloadVersion,
                           :findingCount, :payload, :now, :now
                    FROM review_jobs AS job
                    WHERE job.id = :reviewJobId
                      AND job.tenant_id = :tenantId
                      AND job.tenant_repository_id = :tenantRepositoryId
                      AND job.github_installation_id = :installationId
                      AND job.github_repository_id = :repositoryId
                      AND job.github_pull_request_number = :pullRequestNumber
                      AND job.github_head_sha = :headSha
                    ON CONFLICT (review_job_id) DO NOTHING
                    """).param("id", UUID.randomUUID())
                    .param("tenantId", tenantContext.tenantId())
                    .param("tenantRepositoryId", tenantContext.repositoryId())
                    .param("reviewJobId", reviewJobId)
                    .param("payloadVersion", PAYLOAD_VERSION)
                    .param("findingCount", analysis.findings().size())
                    .param("payload", payload)
                    .param("now", timestamp)
                    .param("installationId", analysis.target().installationId())
                    .param("repositoryId", analysis.target().repositoryId())
                    .param("pullRequestNumber", analysis.target().pullRequestNumber())
                    .param("headSha", analysis.target().headSha())
                    .update();

            if (inserted == 0) {
                ReviewCandidateAnalysis existing = findRow(tenantContext, reviewJobId)
                        .map(this::decode)
                        .orElseThrow(JdbcReviewAnalysisCheckpointStore::inconsistent);
                if (!existing.equals(analysis) || !usageIsConsumed(tenantContext, reviewJobId)) {
                    throw inconsistent();
                }
                return ReviewAnalysisCheckpointResult.EXISTING;
            }

            int consumed = jdbc.sql("""
                    UPDATE tenant_usage_events
                    SET status = 'CONSUMED', consumed_at = :now, updated_at = :now,
                        provider = :provider, model = :model,
                        input_tokens = :inputTokens, cached_input_tokens = :cachedInputTokens,
                        output_tokens = :outputTokens, reasoning_tokens = :reasoningTokens,
                        total_tokens = :totalTokens
                    WHERE tenant_id = :tenantId
                      AND tenant_repository_id = :tenantRepositoryId
                      AND review_job_id = :reviewJobId
                      AND usage_type = 'REVIEW_ANALYSIS'
                      AND status = 'RESERVED'
                    """).param("now", timestamp)
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
                    .update();
            if (consumed != 1) {
                throw inconsistent();
            }
            return ReviewAnalysisCheckpointResult.CREATED;
        } catch (ReviewAnalysisCheckpointException exception) {
            throw exception;
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw failed();
        }
    }

    private Optional<Row> findRow(TenantContext tenantContext, UUID reviewJobId) {
        return jdbc.sql("""
                SELECT checkpoint.payload_version, checkpoint.finding_count, checkpoint.findings_payload,
                       job.github_installation_id, job.github_repository_id,
                       job.github_pull_request_number, job.github_head_sha
                FROM review_analysis_checkpoints AS checkpoint
                JOIN review_jobs AS job ON job.id = checkpoint.review_job_id
                WHERE checkpoint.review_job_id = :reviewJobId
                  AND checkpoint.tenant_id = :tenantId
                  AND checkpoint.tenant_repository_id = :tenantRepositoryId
                """).param("reviewJobId", reviewJobId)
                .param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .query(this::row).optional();
    }

    private boolean usageIsConsumed(TenantContext tenantContext, UUID reviewJobId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM tenant_usage_events
                    WHERE tenant_id = :tenantId
                      AND tenant_repository_id = :tenantRepositoryId
                      AND review_job_id = :reviewJobId
                      AND usage_type = 'REVIEW_ANALYSIS'
                      AND status = 'CONSUMED'
                )
                """).param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .param("reviewJobId", reviewJobId)
                .query(Boolean.class).single();
    }

    private Row row(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Row(
                resultSet.getInt("payload_version"),
                resultSet.getInt("finding_count"),
                resultSet.getString("findings_payload"),
                resultSet.getLong("github_installation_id"),
                resultSet.getLong("github_repository_id"),
                resultSet.getInt("github_pull_request_number"),
                resultSet.getString("github_head_sha"));
    }

    private ReviewCandidateAnalysis decode(Row row) {
        if (row.payloadVersion() != PAYLOAD_VERSION) throw inconsistent();
        ReviewTarget target;
        try {
            target = new ReviewTarget(row.installationId(), row.repositoryId(), row.pullRequestNumber(), row.headSha());
        } catch (IllegalArgumentException exception) {
            throw inconsistent();
        }
        return new ReviewCandidateAnalysis(target, codec.decode(row.payload(), row.findingCount()));
    }

    private static void requireIdentity(TenantContext tenantContext, UUID reviewJobId) {
        if (tenantContext == null || reviewJobId == null) {
            throw new IllegalArgumentException("checkpoint identity is required");
        }
    }

    private static Long boxed(java.util.OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    private static ReviewAnalysisCheckpointException inconsistent() {
        return new ReviewAnalysisCheckpointException(ReviewAnalysisCheckpointError.INCONSISTENT_STATE);
    }

    private static ReviewAnalysisCheckpointException failed() {
        return new ReviewAnalysisCheckpointException(ReviewAnalysisCheckpointError.PERSISTENCE_FAILED);
    }

    private record Row(int payloadVersion, int findingCount, String payload,
            long installationId, long repositoryId, int pullRequestNumber, String headSha) { }
}
