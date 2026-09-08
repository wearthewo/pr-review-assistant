package io.prreviewassistant.review.publication;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ClaimedPublicationJob(UUID id, UUID publicationId, UUID claimToken, int attempt,
        int maxAttempts, Instant claimedAt, Instant claimExpiresAt) {
    public ClaimedPublicationJob {
        Objects.requireNonNull(id); Objects.requireNonNull(publicationId); Objects.requireNonNull(claimToken);
        Objects.requireNonNull(claimedAt); Objects.requireNonNull(claimExpiresAt);
        if (attempt < 1 || maxAttempts < attempt || !claimExpiresAt.isAfter(claimedAt)) {
            throw new IllegalArgumentException("publication claim is invalid");
        }
    }

    @Override public String toString() {
        return "ClaimedPublicationJob[id=" + id + ", publicationId=" + publicationId
                + ", claimToken=<redacted>, attempt=" + attempt + "]";
    }
}
