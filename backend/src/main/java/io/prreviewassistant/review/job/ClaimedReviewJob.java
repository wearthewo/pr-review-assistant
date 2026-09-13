package io.prreviewassistant.review.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import io.prreviewassistant.tenant.TenantContext;

public record ClaimedReviewJob(
        UUID id,
        UUID claimToken,
        int attempt,
        int maxAttempts,
        Instant claimedAt,
        Instant claimExpiresAt,
        ReviewTarget reviewTarget,
        TenantContext tenantContext) {

    public ClaimedReviewJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(claimToken, "claimToken must not be null");
        Objects.requireNonNull(claimedAt, "claimedAt must not be null");
        Objects.requireNonNull(claimExpiresAt, "claimExpiresAt must not be null");
        if (attempt < 1 || maxAttempts < attempt || !claimExpiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException("claim state is invalid");
        }
    }

    public ClaimedReviewJob(UUID id, UUID claimToken, int attempt, int maxAttempts,
            Instant claimedAt, Instant claimExpiresAt, ReviewTarget reviewTarget) {
        this(id, claimToken, attempt, maxAttempts, claimedAt, claimExpiresAt, reviewTarget, null);
    }

    public ClaimedReviewJob(
            UUID id,
            UUID claimToken,
            int attempt,
            int maxAttempts,
            Instant claimedAt,
            Instant claimExpiresAt) {
        this(id, claimToken, attempt, maxAttempts, claimedAt, claimExpiresAt, null, null);
    }

    @Override
    public String toString() {
        return "ClaimedReviewJob[id=" + id + ", claimToken=<redacted>, attempt=" + attempt
                + ", maxAttempts=" + maxAttempts + ", claimedAt=" + claimedAt
                + ", claimExpiresAt=" + claimExpiresAt + "]";
    }
}
