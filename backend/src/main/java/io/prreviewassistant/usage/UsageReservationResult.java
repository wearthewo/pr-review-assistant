package io.prreviewassistant.usage;

import java.util.Objects;

public record UsageReservationResult(Outcome outcome, QuotaDecision quota) {
    public enum Outcome { ACQUIRED, QUOTA_EXCEEDED, EXISTING_RESERVED, EXISTING_CONSUMED, EXISTING_RELEASED }

    public UsageReservationResult {
        Objects.requireNonNull(outcome, "outcome is required");
        Objects.requireNonNull(quota, "quota is required");
    }
}
