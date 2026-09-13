package io.prreviewassistant.usage;

import java.util.Objects;
import java.util.UUID;

public record UsageSummary(UUID tenantId, UsagePeriod period, long reviewAnalyses,
        MeasuredTotal inputTokens, MeasuredTotal cachedInputTokens,
        MeasuredTotal outputTokens, MeasuredTotal reasoningTokens, MeasuredTotal totalTokens) {
    public UsageSummary {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(period, "period is required");
        Objects.requireNonNull(inputTokens, "inputTokens is required");
        Objects.requireNonNull(cachedInputTokens, "cachedInputTokens is required");
        Objects.requireNonNull(outputTokens, "outputTokens is required");
        Objects.requireNonNull(reasoningTokens, "reasoningTokens is required");
        Objects.requireNonNull(totalTokens, "totalTokens is required");
        if (reviewAnalyses < 0) {
            throw new IllegalArgumentException("reviewAnalyses must be non-negative");
        }
    }

    @Override
    public String toString() {
        return "UsageSummary[tenantId=" + tenantId + ", period=" + period
                + ", reviewAnalyses=" + reviewAnalyses + ", tokenMeasurements=<redacted>]";
    }
}
