package io.prreviewassistant.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.prreviewassistant.observability.ApplicationMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ObservedAiProviderTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
    private static final StructuredAiRequest REQUEST = new StructuredAiRequest(
            "instructions", "input", new StructuredOutputSchema("review", "{}", true),
            new AiGenerationProfile(AiModelTier.BALANCED, AiReasoningEffort.LOW, 128));

    @Test
    void recordsOneLogicalSuccessAndProviderTokenMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiProvider delegate = ignored -> new StructuredAiResponse("{}",
                new AiTokenUsage(OptionalLong.of(10), OptionalLong.of(2), OptionalLong.of(4),
                        OptionalLong.of(1), OptionalLong.of(14)),
                new AiExecutionMetadata("fake", "fake-model", Optional.empty(), Duration.ZERO, 2));
        ObservedAiProvider provider = new ObservedAiProvider(
                delegate, new ApplicationMetrics(registry), CLOCK);

        provider.generateStructured(REQUEST);

        assertThat(registry.get(ApplicationMetrics.PREFIX + ".ai.requests")
                .tags("outcome", "success", "error_type", "none").counter().count()).isEqualTo(1);
        assertThat(registry.get(ApplicationMetrics.PREFIX + ".ai.tokens")
                .tag("type", "total").counter().count()).isEqualTo(14);
    }

    @Test
    void recordsBoundedFailureClassificationWithoutRequestContent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ObservedAiProvider provider = new ObservedAiProvider(
                ignored -> { throw new AiProviderException(AiProviderErrorType.RATE_LIMITED); },
                new ApplicationMetrics(registry), CLOCK);

        assertThatThrownBy(() -> provider.generateStructured(REQUEST))
                .isInstanceOf(AiProviderException.class);

        assertThat(registry.get(ApplicationMetrics.PREFIX + ".ai.requests")
                .tags("outcome", "failure", "error_type", "rate_limited").counter().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().toString())
                .doesNotContain("instructions", "input"));
    }
}
