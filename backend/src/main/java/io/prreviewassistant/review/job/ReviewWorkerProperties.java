package io.prreviewassistant.review.job;

import java.time.Duration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("review.worker")
public record ReviewWorkerProperties(
        boolean enabled,
        @NotNull Duration pollInterval,
        @Min(1) @Max(100) int batchSize,
        @NotNull Duration leaseDuration) {

    public ReviewWorkerProperties {
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        requirePositive(pollInterval, "pollInterval");
        requirePositive(leaseDuration, "leaseDuration");
    }

    private static void requirePositive(Duration value, String name) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
