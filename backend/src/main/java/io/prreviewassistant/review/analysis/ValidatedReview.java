package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.job.ReviewTarget;

import java.util.List;
import java.util.Objects;

public record ValidatedReview(
        ReviewTarget target,
        List<ReviewFinding> findings,
        SuppressionSummary suppression) {

    public ValidatedReview {
        Objects.requireNonNull(target, "target is required");
        findings = List.copyOf(findings);
        Objects.requireNonNull(suppression, "suppression is required");
        if (findings.size() != suppression.acceptedCount()) {
            throw new IllegalArgumentException("accepted finding count is inconsistent");
        }
    }

    public ValidatedReview(ReviewTarget target, List<ReviewFinding> findings,
            SuppressionSummary suppression, ReviewAnalysisMetadata ignoredMetadata) {
        this(target, findings, suppression);
        Objects.requireNonNull(ignoredMetadata, "analysisMetadata is required");
    }

    @Override
    public String toString() {
        return "ValidatedReview[candidates=" + suppression.candidateCount()
                + ", accepted=" + suppression.acceptedCount()
                + ", suppressed=" + suppression.suppressedCount() + "]";
    }
}
