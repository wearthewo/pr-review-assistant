package io.prreviewassistant.review.job;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("review.job")
public record ReviewJobProperties(
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration retryBaseDelay,
        @NotNull Duration retryMaxDelay) {

    public ReviewJobProperties {
        if (maxAttempts < 1 || maxAttempts > 100) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        requirePositive(retryBaseDelay, "retryBaseDelay");
        requirePositive(retryMaxDelay, "retryMaxDelay");
        if (retryBaseDelay != null && retryMaxDelay != null
                && retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("retryMaxDelay must not be less than retryBaseDelay");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
