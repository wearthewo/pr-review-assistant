package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.job.ReviewTarget;

import java.util.List;
import java.util.Objects;

public record ValidatedReview(
        ReviewTarget target,
        List<ReviewFinding> findings,
        SuppressionSummary suppression,
        ReviewAnalysisMetadata analysisMetadata) {

    public ValidatedReview {
        Objects.requireNonNull(target, "target is required");
        findings = List.copyOf(findings);
        Objects.requireNonNull(suppression, "suppression is required");
        Objects.requireNonNull(analysisMetadata, "analysisMetadata is required");
        if (findings.size() != suppression.acceptedCount()) {
            throw new IllegalArgumentException("accepted finding count is inconsistent");
        }
    }

    @Override
    public String toString() {
        return "ValidatedReview[candidates=" + suppression.candidateCount()
                + ", accepted=" + suppression.acceptedCount()
                + ", suppressed=" + suppression.suppressedCount() + "]";
    }
}
