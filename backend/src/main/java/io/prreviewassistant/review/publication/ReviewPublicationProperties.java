package io.prreviewassistant.review.publication;

import java.time.Duration;
import java.util.Objects;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("review.publication")
public record ReviewPublicationProperties(boolean enabled,
        @Min(256) @Max(20000) int maxSummaryChars,
        @Min(128) @Max(10000) int maxCommentChars,
        @Min(1024) @Max(100000) int maxPayloadChars,
        @Min(1) @Max(20) int reconciliationMaxPages,
        @Min(1) @Max(100) int maxAttempts,
        @NotNull Duration retryBaseDelay, @NotNull Duration retryMaxDelay,
        @NotNull Duration pollInterval, @Min(1) @Max(100) int batchSize,
        @NotNull Duration leaseDuration) {
    public ReviewPublicationProperties {
        range(maxSummaryChars, 256, 20_000, "maxSummaryChars");
        range(maxCommentChars, 128, 10_000, "maxCommentChars");
        range(maxPayloadChars, 1_024, 100_000, "maxPayloadChars");
        range(reconciliationMaxPages, 1, 20, "reconciliationMaxPages");
        range(maxAttempts, 1, 100, "maxAttempts");
        range(batchSize, 1, 100, "batchSize");
        positive(retryBaseDelay); positive(retryMaxDelay); positive(pollInterval); positive(leaseDuration);
        if (retryBaseDelay != null && retryMaxDelay != null && retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("publication retry maximum must not be below base delay");
        }
    }
    private static void positive(Duration value) {
        Objects.requireNonNull(value, "duration is required");
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException("duration must be positive");
    }

    private static void range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " is outside the supported range");
        }
    }
}
