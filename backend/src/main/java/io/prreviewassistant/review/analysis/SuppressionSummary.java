package io.prreviewassistant.review.analysis;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public record SuppressionSummary(
        int candidateCount,
        int acceptedCount,
        int suppressedCount,
        Map<SuppressionReason, Integer> countsByReason) {

    public SuppressionSummary {
        if (candidateCount < 0 || acceptedCount < 0 || suppressedCount < 0
                || acceptedCount + suppressedCount != candidateCount) {
            throw new IllegalArgumentException("suppression counts are inconsistent");
        }
        EnumMap<SuppressionReason, Integer> copy = new EnumMap<>(SuppressionReason.class);
        countsByReason.forEach((reason, count) -> {
            if (reason == null || count == null || count <= 0) {
                throw new IllegalArgumentException("suppression reason counts must be positive");
            }
            copy.put(reason, count);
        });
        int reasonTotal = copy.values().stream().mapToInt(Integer::intValue).sum();
        if (reasonTotal != suppressedCount) {
            throw new IllegalArgumentException("suppression reason total is inconsistent");
        }
        countsByReason = Collections.unmodifiableMap(copy);
    }
}
