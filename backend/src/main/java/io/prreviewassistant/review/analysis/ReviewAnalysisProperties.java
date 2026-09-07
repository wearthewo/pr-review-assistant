package io.prreviewassistant.review.analysis;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("review.analysis")
@Validated
public record ReviewAnalysisProperties(
        @Min(1) @Max(10) int maxFindings,
        @Min(0) @Max(100) int minimumCandidateConfidence) {

    public ReviewAnalysisProperties {
        if (maxFindings < 1 || maxFindings > 10
                || minimumCandidateConfidence < 0 || minimumCandidateConfidence > 100) {
            throw new IllegalArgumentException("review analysis configuration is invalid");
        }
    }
}
