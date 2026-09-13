package io.prreviewassistant.usage;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("review.usage")
public record UsageProperties(@Min(1) @Max(MAXIMUM_MONTHLY_LIMIT) int monthlyLimit) {
    public static final int MAXIMUM_MONTHLY_LIMIT = 100_000;

    public UsageProperties {
        if (monthlyLimit < 1 || monthlyLimit > MAXIMUM_MONTHLY_LIMIT) {
            throw new IllegalArgumentException("monthlyLimit must be between 1 and " + MAXIMUM_MONTHLY_LIMIT);
        }
    }
}
