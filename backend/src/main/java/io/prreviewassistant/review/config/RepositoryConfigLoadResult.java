package io.prreviewassistant.review.config;

import java.util.Objects;

public record RepositoryConfigLoadResult(
        RepositoryConfigStatus status,
        EffectiveRepositoryReviewConfig effectiveConfig) {

    public RepositoryConfigLoadResult {
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(effectiveConfig, "effectiveConfig is required");
    }

    static RepositoryConfigLoadResult defaults(RepositoryConfigStatus status) {
        return new RepositoryConfigLoadResult(status, EffectiveRepositoryReviewConfig.defaults());
    }

    @Override
    public String toString() {
        return "RepositoryConfigLoadResult[status=" + status + ", effectiveConfig=" + effectiveConfig + "]";
    }
}
