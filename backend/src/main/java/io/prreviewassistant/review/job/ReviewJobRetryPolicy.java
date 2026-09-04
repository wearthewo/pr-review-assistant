package io.prreviewassistant.review.job;

import java.time.Duration;
import java.util.Objects;

public final class ReviewJobRetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;

    public ReviewJobRetryPolicy(Duration baseDelay, Duration maxDelay) {
        this.baseDelay = requirePositive(baseDelay, "baseDelay");
        this.maxDelay = requirePositive(maxDelay, "maxDelay");
        if (maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must not be less than baseDelay");
        }
    }

    public Duration delayAfterAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        Duration delay = baseDelay;
        Duration halfMaximum = maxDelay.dividedBy(2);
        for (int current = 1; current < attempt && delay.compareTo(maxDelay) < 0; current++) {
            if (delay.compareTo(halfMaximum) > 0) {
                return maxDelay;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
