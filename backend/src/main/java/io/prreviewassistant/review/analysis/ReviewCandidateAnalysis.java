package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.job.ReviewTarget;
import java.util.List;
import java.util.Objects;

/** Provider-neutral, validated candidate findings safe to checkpoint. */
public record ReviewCandidateAnalysis(ReviewTarget target, List<ReviewFinding> findings) {
    public static final int MAX_FINDINGS = 10;

    public ReviewCandidateAnalysis {
        Objects.requireNonNull(target, "target is required");
        findings = List.copyOf(findings);
        if (findings.size() > MAX_FINDINGS || findings.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidate analysis is invalid");
        }
    }

    @Override
    public String toString() {
        return "ReviewCandidateAnalysis[findings=" + findings.size() + "]";
    }
}
