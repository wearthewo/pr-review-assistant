package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.job.ReviewTarget;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.prreviewassistant.tenant.TenantContext;

public interface PublicationStore {
    PublicationHandoffResult create(UUID analysisJobId, TenantContext tenantContext,
            ReviewTarget target, String owner, String repository,
            String publicationKey, int findingCount, String encodedPayload, int maxAttempts, Instant now);
    boolean existsForAnalysisJob(UUID analysisJobId);
    List<ClaimedPublicationJob> claimDue(Instant now, Duration lease, int batchSize);
    Optional<ReviewPublication> find(UUID publicationId);
    boolean markAmbiguous(UUID publicationId, Instant now);
    boolean markPublished(UUID publicationId, long githubReviewId, Instant publishedAt, Instant now);
    boolean markPublicationFailed(UUID publicationId, String errorCode, Instant now);
    boolean completeJob(UUID jobId, UUID claimToken, Instant now);
    boolean retryJob(UUID jobId, UUID claimToken, Instant nextAttemptAt, String errorCode, Instant now);
    boolean failPublicationAndJob(
            UUID jobId, UUID publicationId, UUID claimToken, String errorCode, Instant now);
}
