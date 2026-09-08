package io.prreviewassistant.review.analysis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.suppression")
@Validated
public record FindingSuppressionProperties(
        @Min(0) @Max(100) int minimumConfidence,
        @NotNull ReviewSeverity minimumSeverity,
        @Min(1) @Max(5) int maxPublishableFindings) {

    public FindingSuppressionProperties {
        if (minimumConfidence < 0 || minimumConfidence > 100
                || minimumSeverity == null
                || maxPublishableFindings < 1 || maxPublishableFindings > 5) {
            throw new IllegalArgumentException("finding suppression configuration is invalid");
        }
    }
}
