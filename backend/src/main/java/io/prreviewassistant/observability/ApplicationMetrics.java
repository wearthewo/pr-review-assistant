package io.prreviewassistant.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.analysis.ReviewSeverity;
import io.prreviewassistant.review.analysis.SuppressionReason;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-cardinality application metrics. Every public method accepts only controlled values;
 * workflow and tenant identifiers belong in logs, never metric tags.
 */
public final class ApplicationMetrics {

    public static final String PREFIX = "pr.review.assistant";

    private final MeterRegistry registry;

    public ApplicationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public static ApplicationMetrics noop() {
        return new ApplicationMetrics(new SimpleMeterRegistry());
    }

    public void webhook(String outcome, String reason, Duration duration) {
        increment("webhook.requests", tags("outcome", outcome, "reason", reason), 1);
        record("webhook.duration", tags("outcome", outcome), duration);
    }

    public void webhookRejected(String reason) {
        increment("webhook.requests", tags("outcome", "rejected", "reason", reason), 1);
    }

    public void reviewClaims(int count) {
        increment("review.jobs.claimed", List.of(), count);
    }

    public void staleReviewClaimsRecovered(int count) {
        increment("review.jobs.stale.recovered", List.of(), count);
    }

    public void reviewJob(String outcome, Duration duration) {
        increment("review.jobs.outcomes", tags("outcome", outcome), 1);
        record("review.jobs.duration", tags("outcome", outcome), duration);
    }

    public void github(GitHubOperation operation, String outcome, Duration duration) {
        increment("github.operations", tags("operation", operation.tag(), "outcome", outcome), 1);
        record("github.duration", tags("operation", operation.tag(), "outcome", outcome), duration);
    }

    public void ai(String outcome, String errorType, Duration duration) {
        increment("ai.requests", tags("outcome", outcome, "error_type", errorType), 1);
        record("ai.duration", tags("outcome", outcome), duration);
    }

    public void aiTokens(String type, long count) {
        increment("ai.tokens", tags("type", type), count);
    }

    public void findingsGenerated(ReviewFindingCategory category, ReviewSeverity severity, int count) {
        increment("review.findings", tags("stage", "candidate", "category", tag(category),
                "severity", tag(severity)), count);
    }

    public void findingsAccepted(ReviewFindingCategory category, ReviewSeverity severity, int count) {
        increment("review.findings", tags("stage", "accepted", "category", tag(category),
                "severity", tag(severity)), count);
    }

    public void findingsSuppressed(SuppressionReason reason, int count) {
        increment("review.findings.suppressed", tags("reason", tag(reason)), count);
    }

    public void publication(String outcome, Duration duration) {
        increment("publication.attempts", tags("outcome", outcome), 1);
        record("publication.duration", tags("outcome", outcome), duration);
    }

    public void publicationReconciled() {
        increment("publication.reconciled", List.of(), 1);
    }

    public void usage(String action, String outcome) {
        increment("usage.events", tags("action", action, "outcome", outcome), 1);
    }

    private void increment(String suffix, List<Tag> tags, double amount) {
        if (amount <= 0) {
            return;
        }
        safely(() -> registry.counter(PREFIX + "." + suffix, tags).increment(amount));
    }

    private void record(String suffix, List<Tag> tags, Duration duration) {
        Duration safeDuration = duration == null || duration.isNegative() ? Duration.ZERO : duration;
        safely(() -> Timer.builder(PREFIX + "." + suffix)
                .tags(tags)
                .register(registry)
                .record(safeDuration));
    }

    private void safely(Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException ignored) {
            // Telemetry must never change authoritative business behavior.
        }
    }

    private static List<Tag> tags(String... values) {
        List<Tag> tags = new ArrayList<>(values.length / 2);
        for (int index = 0; index < values.length; index += 2) {
            tags.add(Tag.of(values[index], values[index + 1]));
        }
        return List.copyOf(tags);
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }

    public enum GitHubOperation {
        INSTALLATION_TOKEN,
        REPOSITORY,
        PULL_REQUEST,
        CHANGED_FILES,
        CONTENTS,
        PUBLISH_REVIEW,
        RECONCILE_REVIEW,
        OAUTH_TOKEN,
        OAUTH_USER,
        OAUTH_INSTALLATIONS;

        String tag() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }
}
