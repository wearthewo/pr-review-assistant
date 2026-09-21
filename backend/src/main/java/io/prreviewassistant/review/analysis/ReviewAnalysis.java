package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.job.ReviewTarget;

import java.util.List;
import java.util.Objects;

public record ReviewAnalysis(
        ReviewTarget target,
        List<ReviewFinding> findings,
        ReviewAnalysisMetadata metadata) {

    public ReviewAnalysis {
        Objects.requireNonNull(target, "target is required");
        findings = List.copyOf(findings);
        Objects.requireNonNull(metadata, "metadata is required");
    }

    public ReviewCandidateAnalysis candidates() {
        return new ReviewCandidateAnalysis(target, findings);
    }

    @Override
    public String toString() {
        return "ReviewAnalysis[findings=" + findings.size() + ", modelTier=" + metadata.modelTier() + "]";
    }
}
