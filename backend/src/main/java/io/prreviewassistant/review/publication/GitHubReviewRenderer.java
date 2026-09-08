package io.prreviewassistant.review.publication;

import io.prreviewassistant.review.analysis.ReviewFinding;
import io.prreviewassistant.review.analysis.ValidatedReview;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class GitHubReviewRenderer {

    public static final String MARKER_PREFIX = "<!-- pr-review-assistant:publication:";

    private static final Pattern HTML = Pattern.compile("(?s)<!--.*?-->|<[^>]*>");
    private static final Pattern MENTION = Pattern.compile("(?<![\\w`])@(?=[A-Za-z0-9])");
    private static final Pattern ACTIVE_LINE_PREFIX = Pattern.compile("^(\\s*)(#|>|[-*]\\s+\\[)");
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\\n{3,}");

    private final ReviewPublicationProperties properties;

    public GitHubReviewRenderer(ReviewPublicationProperties properties) {
        this.properties = properties;
    }

    public PublicationPayload render(ValidatedReview review, String key) {
        if (!key.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("publication key is invalid");
        }

        List<PublicationComment> comments = new ArrayList<>();
        StringBuilder summary = new StringBuilder("Automated review: ")
                .append(review.findings().size())
                .append(" review findings passed automated validation.\n");
        List<ReviewFinding> fileFindings = review.findings().stream()
                .filter(finding -> finding.startLine() == null)
                .toList();
        if (!fileFindings.isEmpty()) {
            summary.append("\nFile-level findings:\n");
            for (ReviewFinding finding : fileFindings) {
                summary.append("\n- **")
                        .append(sanitize(finding.title()))
                        .append("** (`")
                        .append(sanitize(finding.path()))
                        .append("`): ")
                        .append(sanitize(finding.explanation()))
                        .append('\n');
            }
        }

        String marker = MARKER_PREFIX + key + " -->";
        int summaryContentLimit = properties.maxSummaryChars() - marker.length() - 1;
        if (summaryContentLimit < 1) {
            throw new IllegalArgumentException("publication summary limit cannot contain marker");
        }
        String body = bounded(summary.toString(), summaryContentLimit) + "\n" + marker;

        for (ReviewFinding finding : review.findings()) {
            if (finding.startLine() == null) {
                continue;
            }
            StringBuilder text = new StringBuilder("**")
                    .append(sanitize(finding.title()))
                    .append("** - ")
                    .append(finding.severity())
                    .append(" severity, ")
                    .append(finding.confidence())
                    .append("% confidence\n\n")
                    .append(sanitize(finding.explanation()))
                    .append("\n\nEvidence: ")
                    .append(sanitize(finding.evidence()))
                    .append("\n\nImpact: ")
                    .append(sanitize(finding.impact()));
            if (finding.suggestedFix() != null) {
                text.append("\n\nSuggested fix: ").append(sanitize(finding.suggestedFix()));
            }
            comments.add(new PublicationComment(
                    finding.path(),
                    finding.endLine(),
                    finding.startLine().equals(finding.endLine()) ? null : finding.startLine(),
                    bounded(text.toString(), properties.maxCommentChars())));
        }

        PublicationPayload result = new PublicationPayload(PublicationPayload.CURRENT_VERSION, body, comments);
        int total = body.length() + comments.stream().mapToInt(comment -> comment.body().length()).sum();
        if (total > properties.maxPayloadChars()) {
            throw new IllegalArgumentException("publication content exceeds configured limit");
        }
        return result;
    }

    static String sanitize(String value) {
        String clean = value.replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ");
        clean = HTML.matcher(clean).replaceAll(" ");
        clean = clean.replace("```", "` ` `").replace("](", "] (");
        clean = MENTION.matcher(clean).replaceAll("@\u200B");
        clean = clean.lines()
                .map(line -> ACTIVE_LINE_PREFIX.matcher(line).replaceFirst("$1\\\\$2"))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return EXCESS_BLANK_LINES.matcher(clean).replaceAll("\n\n").trim();
    }

    private static String bounded(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 14)).stripTrailing() + "\n[truncated]";
    }
}
