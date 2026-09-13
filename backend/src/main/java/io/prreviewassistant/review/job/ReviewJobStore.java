package io.prreviewassistant.review.job;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import io.prreviewassistant.tenant.TenantContext;

public interface ReviewJobStore {

    ReviewJob create(int maxAttempts, Instant now);

    ReviewJobCreationResult createForReviewTarget(
            TenantContext tenantContext, ReviewTarget target, int maxAttempts, Instant now);

    List<ClaimedReviewJob> claimDue(Instant now, Duration leaseDuration, int batchSize);

    boolean complete(UUID jobId, UUID claimToken, Instant now);

    boolean retry(UUID jobId, UUID claimToken, Instant nextAttemptAt, ReviewJobErrorCode errorCode, Instant now);

    boolean fail(UUID jobId, UUID claimToken, ReviewJobErrorCode errorCode, Instant now);
}
