package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.job.ReviewTarget;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import io.prreviewassistant.tenant.TenantContext;

@Repository
public class JdbcPublicationStore implements PublicationStore {
    private final JdbcClient jdbc;
    public JdbcPublicationStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public PublicationHandoffResult create(UUID analysisJobId, TenantContext tenantContext,
            ReviewTarget target, String owner, String repository,
            String key, int findingCount, String payload, int maxAttempts, Instant now) {
        if (tenantContext == null
                || tenantContext.githubInstallationId() != target.installationId()
                || tenantContext.githubRepositoryId() != target.repositoryId()) {
            throw new IllegalArgumentException("tenant context does not own publication target");
        }
        UUID publicationId = UUID.randomUUID(); UUID jobId = UUID.randomUUID(); OffsetDateTime timestamp = utc(now);
        int inserted = jdbc.sql("""
                INSERT INTO review_publications
                  (id, analysis_job_id, tenant_id, tenant_repository_id,
                   github_installation_id, github_repository_id,
                   github_repository_owner, github_repository_name, github_pull_request_number,
                   github_head_sha, publication_key, payload_version, finding_count, payload,
                   status, created_at, updated_at)
                VALUES (:id, :analysisJobId, :tenantId, :tenantRepositoryId,
                        :installationId, :repositoryId, :owner, :repository,
                        :pullNumber, :headSha, :key, 1, :findingCount, :payload, 'PENDING', :now, :now)
                ON CONFLICT DO NOTHING
                """).param("id", publicationId).param("analysisJobId", analysisJobId)
                .param("tenantId", tenantContext.tenantId())
                .param("tenantRepositoryId", tenantContext.repositoryId())
                .param("installationId", target.installationId()).param("repositoryId", target.repositoryId())
                .param("owner", owner).param("repository", repository).param("pullNumber", target.pullRequestNumber())
                .param("headSha", target.headSha()).param("key", key).param("findingCount", findingCount)
                .param("payload", payload).param("now", timestamp).update();
        if (inserted == 0) return PublicationHandoffResult.ALREADY_EXISTS;
        jdbc.sql("""
                INSERT INTO publication_jobs
                  (id, publication_id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at)
                VALUES (:id, :publicationId, 'READY', 0, :maxAttempts, :now, :now, :now)
                """).param("id", jobId).param("publicationId", publicationId)
                .param("maxAttempts", maxAttempts).param("now", timestamp).update();
        return PublicationHandoffResult.CREATED;
    }

    @Override
    public boolean existsForAnalysisJob(UUID analysisJobId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM review_publications WHERE analysis_job_id = :analysisJobId)")
                .param("analysisJobId", analysisJobId)
                .query(Boolean.class)
                .single();
    }

    @Override @Transactional
    public List<ClaimedPublicationJob> claimDue(Instant now, Duration lease, int batchSize) {
        OffsetDateTime timestamp=utc(now); expireExhausted(timestamp, batchSize);
        List<Candidate> candidates=jdbc.sql("""
                SELECT id, publication_id, attempts, max_attempts FROM publication_jobs
                WHERE (status='READY' AND next_attempt_at<=:now)
                   OR (status='PROCESSING' AND claim_expires_at<=:now AND attempts<max_attempts)
                ORDER BY next_attempt_at, created_at, id FOR UPDATE SKIP LOCKED LIMIT :limit
                """).param("now",timestamp).param("limit",batchSize)
                .query((rs,n)->new Candidate(rs.getObject("id",UUID.class),rs.getObject("publication_id",UUID.class),
                        rs.getInt("attempts"),rs.getInt("max_attempts"))).list();
        List<ClaimedPublicationJob> result=new ArrayList<>(); Instant expiry=now.plus(lease);
        for(Candidate c:candidates){UUID token=UUID.randomUUID();int attempt=c.attempts+1;
            jdbc.sql("UPDATE publication_jobs SET status='PROCESSING', attempts=:attempt, claim_token=:token, "
                    + "claimed_at=:now, claim_expires_at=:expiry, updated_at=:now WHERE id=:id")
                    .param("attempt",attempt).param("token",token).param("now",timestamp).param("expiry",utc(expiry))
                    .param("id",c.id).update();
            result.add(new ClaimedPublicationJob(c.id,c.publicationId,token,attempt,c.maxAttempts,now,expiry));}
        return List.copyOf(result);
    }

    @Override public Optional<ReviewPublication> find(UUID id) {
        return jdbc.sql("SELECT id, analysis_job_id, tenant_id, tenant_repository_id, github_installation_id, github_repository_id, "
                + "github_repository_owner, github_repository_name, github_pull_request_number, github_head_sha, "
                + "publication_key, payload_version, finding_count, payload, status, github_review_id, published_at "
                + "FROM review_publications WHERE id=:id").param("id",id).query((rs,n)->new ReviewPublication(
                rs.getObject("id",UUID.class),rs.getObject("analysis_job_id",UUID.class),
                rs.getObject("tenant_id",UUID.class),rs.getObject("tenant_repository_id",UUID.class),
                rs.getLong("github_installation_id"),
                rs.getLong("github_repository_id"),rs.getString("github_repository_owner"),rs.getString("github_repository_name"),
                rs.getInt("github_pull_request_number"),rs.getString("github_head_sha"),rs.getString("publication_key").trim(),
                rs.getInt("payload_version"),rs.getInt("finding_count"),rs.getString("payload"),
                PublicationStatus.valueOf(rs.getString("status")),(Long)rs.getObject("github_review_id"),
                instant(rs.getObject("published_at",OffsetDateTime.class)))).optional();
    }

    @Override @Transactional public boolean markAmbiguous(UUID id,Instant now){return jdbc.sql("""
            UPDATE review_publications SET status='AMBIGUOUS', updated_at=:now
            WHERE id=:id AND status IN ('PENDING','AMBIGUOUS')""").param("now",utc(now)).param("id",id).update()==1;}
    @Override @Transactional public boolean markPublished(UUID id,long reviewId,Instant publishedAt,Instant now){return jdbc.sql("""
            UPDATE review_publications SET status='PUBLISHED', github_review_id=:reviewId, published_at=:publishedAt,
            last_error_code=NULL, updated_at=:now WHERE id=:id AND status='AMBIGUOUS'""")
            .param("reviewId",reviewId).param("publishedAt",utc(publishedAt)).param("now",utc(now)).param("id",id).update()==1;}
    @Override @Transactional public boolean markPublicationFailed(UUID id,String code,Instant now){return jdbc.sql("""
            UPDATE review_publications SET status='FAILED', last_error_code=:code, updated_at=:now
            WHERE id=:id AND status IN ('PENDING','AMBIGUOUS')""").param("code",code).param("now",utc(now)).param("id",id).update()==1;}

    @Override @Transactional public boolean completeJob(UUID id,UUID token,Instant now){return transition(id,token,"""
            status='COMPLETED', next_attempt_at=NULL, claim_token=NULL, claimed_at=NULL, claim_expires_at=NULL,
            completed_at=:now, updated_at=:now""",null,null,now);}
    @Override @Transactional public boolean retryJob(UUID id,UUID token,Instant next,String code,Instant now){return transition(id,token,"""
            status='READY', next_attempt_at=:next, claim_token=NULL, claimed_at=NULL, claim_expires_at=NULL,
            last_error_code=:code, updated_at=:now""",next,code,now);}
    @Override
    @Transactional
    public boolean failPublicationAndJob(
            UUID id, UUID publicationId, UUID token, String code, Instant now) {
        boolean owned = transition(id, token, """
                status='FAILED', next_attempt_at=NULL, claim_token=NULL, claimed_at=NULL, claim_expires_at=NULL,
                failed_at=:now, last_error_code=:code, updated_at=:now""", null, code, now);
        if (!owned) {
            return false;
        }
        markPublicationFailed(publicationId, code, now);
        return true;
    }

    private boolean transition(UUID id,UUID token,String assignments,Instant next,String code,Instant now){
        JdbcClient.StatementSpec spec=jdbc.sql("UPDATE publication_jobs SET "+assignments+
                " WHERE id=:id AND status='PROCESSING' AND claim_token=:token")
                .param("id",id).param("token",token).param("now",utc(now));
        if(next!=null)spec.param("next",utc(next)); if(code!=null)spec.param("code",code); return spec.update()==1;
    }
    private void expireExhausted(OffsetDateTime now,int limit){jdbc.sql("""
            WITH exhausted AS (
                SELECT id FROM publication_jobs
                WHERE status='PROCESSING' AND claim_expires_at<=:now AND attempts>=max_attempts
                ORDER BY claim_expires_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT :limit
            ), failed_jobs AS (
                UPDATE publication_jobs AS job
                SET status='FAILED', next_attempt_at=NULL, claim_token=NULL, claimed_at=NULL,
                    claim_expires_at=NULL, failed_at=:now,
                    last_error_code='LEASE_EXPIRED_MAX_ATTEMPTS', updated_at=:now
                FROM exhausted WHERE job.id=exhausted.id
                RETURNING job.publication_id
            )
            UPDATE review_publications AS publication
            SET status='FAILED', last_error_code='LEASE_EXPIRED_MAX_ATTEMPTS', updated_at=:now
            WHERE publication.id IN (SELECT publication_id FROM failed_jobs)
              AND publication.status IN ('PENDING','AMBIGUOUS')
            """).param("now",now).param("limit",limit).update();}
    private static OffsetDateTime utc(Instant value){return value.atOffset(ZoneOffset.UTC);}
    private static Instant instant(OffsetDateTime value){return value==null?null:value.toInstant();}
    private record Candidate(UUID id,UUID publicationId,int attempts,int maxAttempts){}
}
