package io.prreviewassistant.review.config;

import io.prreviewassistant.review.context.ReviewContextProperties;

public record ReviewExecutionProfile(
        int maxFiles,
        int maxTotalBytes,
        int maxCandidates,
        int maxApiRequests) {

    public static ReviewExecutionProfile forMode(ReviewMode mode, ReviewContextProperties ceilings) {
        return switch (mode) {
            case FAST -> new ReviewExecutionProfile(
                    half(ceilings.maxFiles()), half(ceilings.maxTotalBytes()),
                    half(ceilings.maxCandidates()), half(ceilings.maxApiRequests()));
            case BALANCED, DEEP -> new ReviewExecutionProfile(
                    ceilings.maxFiles(), ceilings.maxTotalBytes(),
                    ceilings.maxCandidates(), ceilings.maxApiRequests());
        };
    }

    private static int half(int value) {
        return Math.max(1, value / 2);
    }
}
