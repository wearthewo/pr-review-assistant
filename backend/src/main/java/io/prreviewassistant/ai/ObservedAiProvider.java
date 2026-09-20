package io.prreviewassistant.ai;

import io.prreviewassistant.observability.ApplicationMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Records one logical provider invocation; SDK-internal physical retries are not double-counted. */
public final class ObservedAiProvider implements AiProvider {

    private final AiProvider delegate;
    private final ApplicationMetrics metrics;
    private final Clock clock;

    public ObservedAiProvider(AiProvider delegate, ApplicationMetrics metrics, Clock clock) {
        this.delegate = delegate;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public StructuredAiResponse generateStructured(StructuredAiRequest request) {
        Instant startedAt = clock.instant();
        try {
            StructuredAiResponse response = delegate.generateStructured(request);
            metrics.ai("success", "none", Duration.between(startedAt, clock.instant()));
            recordTokens(response.usage());
            return response;
        } catch (AiProviderException exception) {
            metrics.ai("failure", tag(exception.errorType()), Duration.between(startedAt, clock.instant()));
            throw exception;
        } catch (RuntimeException exception) {
            metrics.ai("failure", "unexpected", Duration.between(startedAt, clock.instant()));
            throw exception;
        }
    }

    private void recordTokens(AiTokenUsage usage) {
        usage.inputTokens().ifPresent(value -> metrics.aiTokens("input", value));
        usage.cachedInputTokens().ifPresent(value -> metrics.aiTokens("cached_input", value));
        usage.outputTokens().ifPresent(value -> metrics.aiTokens("output", value));
        usage.reasoningTokens().ifPresent(value -> metrics.aiTokens("reasoning_output", value));
        usage.totalTokens().ifPresent(value -> metrics.aiTokens("total", value));
    }

    private static String tag(Enum<?> value) {
        return value.name().toLowerCase(java.util.Locale.ROOT);
    }
}
