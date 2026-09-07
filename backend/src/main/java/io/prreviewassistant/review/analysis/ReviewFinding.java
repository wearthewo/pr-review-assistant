package io.prreviewassistant.review.analysis;

import java.util.Objects;

public record ReviewFinding(
        String id,
        ReviewFindingCategory category,
        ReviewSeverity severity,
        int confidence,
        String path,
        Integer startLine,
        Integer endLine,
        String title,
        String evidence,
        String impact,
        String explanation,
        String suggestedFix) {

    public static final int MAX_TITLE_LENGTH = 160;
    public static final int MAX_EVIDENCE_LENGTH = 1_000;
    public static final int MAX_IMPACT_LENGTH = 600;
    public static final int MAX_EXPLANATION_LENGTH = 1_500;
    public static final int MAX_SUGGESTED_FIX_LENGTH = 1_000;

    public ReviewFinding {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(category, "category is required");
        Objects.requireNonNull(severity, "severity is required");
        Objects.requireNonNull(path, "path is required");
        validateText(title, MAX_TITLE_LENGTH, "title");
        validateText(evidence, MAX_EVIDENCE_LENGTH, "evidence");
        validateText(impact, MAX_IMPACT_LENGTH, "impact");
        validateText(explanation, MAX_EXPLANATION_LENGTH, "explanation");
        if (!id.matches("[0-9a-f]{64}") || confidence < 0 || confidence > 100
                || path.isBlank() || path.length() > 4096
                || (startLine == null) != (endLine == null)
                || (startLine != null && (startLine < 1 || endLine < startLine))) {
            throw new IllegalArgumentException("review finding is invalid");
        }
        if (suggestedFix != null && (suggestedFix.isBlank()
                || suggestedFix.length() > MAX_SUGGESTED_FIX_LENGTH)) {
            throw new IllegalArgumentException("suggestedFix is invalid");
        }
    }

    private static void validateText(String value, int maximum, String name) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    @Override
    public String toString() {
        return "ReviewFinding[id=" + id + ", category=" + category + ", severity=" + severity
                + ", confidence=" + confidence + ", path=<redacted>, location="
                + (startLine == null ? "absent" : "present")
                + ", content=<redacted>]";
    }
}
