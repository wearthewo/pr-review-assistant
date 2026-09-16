package io.prreviewassistant.dashboard.review;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcDashboardReviewStore implements DashboardReviewStore {
    private static final String SELECT = """
            SELECT job.id, job.github_repository_id, job.github_pull_request_number,
                   job.github_head_sha, job.status AS job_status,
                   publication.status AS publication_status, publication.finding_count,
                   job.created_at,
                   GREATEST(job.updated_at, COALESCE(publication.updated_at, job.updated_at)) AS updated_at
            FROM review_jobs job
            LEFT JOIN review_publications publication
              ON publication.analysis_job_id = job.id
             AND publication.tenant_id = job.tenant_id
            WHERE job.tenant_id = :tenantId
              AND job.github_repository_id IS NOT NULL
              AND job.github_pull_request_number IS NOT NULL
              AND job.github_head_sha IS NOT NULL
            """;
    private static final String ORDER_AND_LIMIT = """
            ORDER BY job.created_at DESC, job.id DESC
            LIMIT :limit
            """;

    private final JdbcClient jdbc;

    JdbcDashboardReviewStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReviewRecord> findByTenant(
            UUID tenantId, DashboardReviewCursorCodec.Cursor cursor, int limit) {
        if (tenantId == null || limit < 1 || limit > DashboardReviewService.MAX_QUERY_SIZE) {
            throw new IllegalArgumentException("review history query is invalid");
        }
        JdbcClient.StatementSpec statement;
        if (cursor == null) {
            statement = jdbc.sql(SELECT + ORDER_AND_LIMIT);
        } else {
            statement = jdbc.sql(SELECT + """
                      AND (job.created_at < :cursorCreatedAt
                           OR (job.created_at = :cursorCreatedAt AND job.id < :cursorId))
                    """ + ORDER_AND_LIMIT)
                    .param("cursorCreatedAt", cursor.createdAt().atOffset(java.time.ZoneOffset.UTC))
                    .param("cursorId", cursor.jobId());
        }
        return statement.param("tenantId", tenantId).param("limit", limit)
                .query((resultSet, row) -> new ReviewRecord(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getLong("github_repository_id"),
                        resultSet.getInt("github_pull_request_number"),
                        resultSet.getString("github_head_sha"),
                        resultSet.getString("job_status"),
                        resultSet.getString("publication_status"),
                        resultSet.getObject("finding_count", Integer.class),
                        resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        resultSet.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
