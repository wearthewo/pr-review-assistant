package io.prreviewassistant.review.publication;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReviewPublication(UUID id, UUID analysisJobId, UUID tenantId, UUID tenantRepositoryId,
        long installationId, long repositoryId,
        String repositoryOwner, String repositoryName, int pullRequestNumber, String headSha,
        String publicationKey, int payloadVersion, int findingCount, String encodedPayload,
        PublicationStatus status, Long githubReviewId, Instant publishedAt) {
    public ReviewPublication {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(analysisJobId, "analysisJobId is required");
        Objects.requireNonNull(status, "status is required");
        if ((tenantId == null) != (tenantRepositoryId == null)) {
            throw new IllegalArgumentException("publication tenant ownership is invalid");
        }
        if (installationId <= 0 || repositoryId <= 0 || pullRequestNumber <= 0
                || repositoryOwner == null || repositoryOwner.isBlank()
                || repositoryOwner.length() > 100
                || repositoryName == null || repositoryName.isBlank()
                || repositoryName.length() > 100
                || headSha == null || !headSha.matches("[0-9a-f]{40,64}")
                || publicationKey == null || !publicationKey.matches("[0-9a-f]{64}")
                || payloadVersion != PublicationPayload.CURRENT_VERSION
                || findingCount < 1 || findingCount > 5
                || encodedPayload == null || encodedPayload.isBlank()
                || (status == PublicationStatus.PUBLISHED)
                        != (githubReviewId != null && githubReviewId > 0 && publishedAt != null)) {
            throw new IllegalArgumentException("review publication is invalid");
        }
    }

    public ReviewPublication(UUID id, UUID analysisJobId, long installationId, long repositoryId,
            String repositoryOwner, String repositoryName, int pullRequestNumber, String headSha,
            String publicationKey, int payloadVersion, int findingCount, String encodedPayload,
            PublicationStatus status, Long githubReviewId, Instant publishedAt) {
        this(id, analysisJobId, null, null, installationId, repositoryId, repositoryOwner,
                repositoryName, pullRequestNumber, headSha, publicationKey, payloadVersion,
                findingCount, encodedPayload, status, githubReviewId, publishedAt);
    }

    @Override public String toString() {
        return "ReviewPublication[id=" + id + ", status=" + status + ", publicationKey="
                + publicationKey.substring(0, 8) + "..., payload=<redacted>]";
    }
}
