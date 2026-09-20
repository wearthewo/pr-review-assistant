package io.prreviewassistant.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.analysis.ReviewSeverity;
import io.prreviewassistant.review.analysis.SuppressionReason;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApplicationMetricsTest {

    @Test
    @SuppressWarnings("unchecked")
    void registryFailureCannotEscapeIntoBusinessProcessing() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.counter(anyString(), any(Iterable.class)))
                .thenThrow(new IllegalStateException("registry unavailable"));

        assertThatCode(() -> new ApplicationMetrics(registry).webhookRejected("invalid_signature"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordsControlledOperationalOutcomesWithoutIdentifierTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationMetrics metrics = new ApplicationMetrics(registry);

        metrics.webhook("accepted", "none", Duration.ofMillis(12));
        metrics.webhook("duplicate", "none", Duration.ofMillis(3));
        metrics.webhookRejected("invalid_signature");
        metrics.reviewJob("completed", Duration.ofSeconds(2));
        metrics.findingsGenerated(ReviewFindingCategory.SECURITY, ReviewSeverity.HIGH, 2);
        metrics.findingsAccepted(ReviewFindingCategory.SECURITY, ReviewSeverity.HIGH, 1);
        metrics.findingsSuppressed(SuppressionReason.LOW_CONFIDENCE, 1);
        metrics.publication("ambiguous", Duration.ofSeconds(1));
        metrics.publicationReconciled();
        metrics.usage("reserve", "exhausted");
        metrics.usage("consume", "consumed");
        metrics.usage("release", "released");

        assertThat(registry.get(ApplicationMetrics.PREFIX + ".webhook.requests")
                .tags("outcome", "accepted", "reason", "none").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".webhook.requests")
                .tags("outcome", "duplicate", "reason", "none").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".webhook.requests")
                .tags("outcome", "rejected", "reason", "invalid_signature").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.findings.suppressed")
                .tag("reason", "low_confidence").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.findings")
                .tags("stage", "candidate", "category", "security", "severity", "high")
                .counter().count()).isEqualTo(2);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".review.findings")
                .tags("stage", "accepted", "category", "security", "severity", "high")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".publication.attempts")
                .tag("outcome", "ambiguous").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".publication.reconciled")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "reserve", "outcome", "exhausted").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "consume", "outcome", "consumed").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".usage.events")
                .tags("action", "release", "outcome", "released").counter().count()).isEqualTo(1);

        Set<String> forbidden = Set.of("tenant_id", "user_id", "review_job_id", "publication_id",
                "github_repository_id", "pull_request_number", "correlation_id", "url", "message");
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .noneMatch(tag -> forbidden.contains(tag.getKey())));
    }
}
