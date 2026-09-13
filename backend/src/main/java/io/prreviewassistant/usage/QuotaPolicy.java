package io.prreviewassistant.usage;

public final class QuotaPolicy {
    private final int monthlyLimit;

    public QuotaPolicy(int monthlyLimit) {
        if (monthlyLimit < 1 || monthlyLimit > UsageProperties.MAXIMUM_MONTHLY_LIMIT) {
            throw new IllegalArgumentException("monthly usage limit is invalid");
        }
        this.monthlyLimit = monthlyLimit;
    }

    public QuotaDecision decide(long used, UsagePeriod period) {
        if (used < 0) {
            throw new IllegalArgumentException("used usage must be non-negative");
        }
        boolean allowed = used < monthlyLimit;
        long remaining = Math.max(0L, (long) monthlyLimit - used);
        return new QuotaDecision(allowed, monthlyLimit, used, remaining, period,
                allowed ? QuotaDecision.Reason.AVAILABLE : QuotaDecision.Reason.EXHAUSTED);
    }

    int monthlyLimit() {
        return monthlyLimit;
    }
}
