package io.prreviewassistant.usage;

import java.util.Objects;

public record QuotaDecision(boolean allowed, int limit, long used, long remaining,
        UsagePeriod period, Reason reason) {
    public enum Reason { AVAILABLE, EXHAUSTED }

    public QuotaDecision {
        Objects.requireNonNull(period, "period is required");
        Objects.requireNonNull(reason, "reason is required");
        if (limit < 1 || used < 0 || remaining < 0 || remaining > limit
                || allowed != (reason == Reason.AVAILABLE)) {
            throw new IllegalArgumentException("quota decision is invalid");
        }
    }
}
