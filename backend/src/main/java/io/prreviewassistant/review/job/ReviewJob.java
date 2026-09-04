package io.prreviewassistant.review.job;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReviewJob(
        UUID id,
        ReviewJobStatus status,
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

    public ReviewJob {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (attempts < 0 || maxAttempts <= 0 || attempts > maxAttempts) {
            throw new IllegalArgumentException("attempt counts are invalid");
        }
    }

    @Override
    public String toString() {
        return "ReviewJob[id=" + id + ", status=" + status + ", attempts=" + attempts
                + ", maxAttempts=" + maxAttempts + ", claimToken=<redacted>]";
    }
}
