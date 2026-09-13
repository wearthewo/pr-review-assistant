package io.prreviewassistant.usage;

import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.review.analysis.ReviewAnalysisMetadata;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

public record UsageMeasurement(Optional<String> provider, Optional<String> model,
        OptionalLong inputTokens, OptionalLong cachedInputTokens, OptionalLong outputTokens,
        OptionalLong reasoningTokens, OptionalLong totalTokens) {
    static final long MAXIMUM_TOKEN_COUNT = 1_000_000_000_000L;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:/-]+");

    public UsageMeasurement {
        provider = safeIdentifier(provider, 64);
        model = safeIdentifier(model, 200);
        inputTokens = safeTokens(inputTokens);
        cachedInputTokens = safeTokens(cachedInputTokens);
        outputTokens = safeTokens(outputTokens);
        reasoningTokens = safeTokens(reasoningTokens);
        totalTokens = safeTokens(totalTokens);
        if (inputTokens.isPresent() && cachedInputTokens.isPresent()
                && cachedInputTokens.getAsLong() > inputTokens.getAsLong()) {
            throw new IllegalArgumentException("cached input usage is invalid");
        }
        if (outputTokens.isPresent() && reasoningTokens.isPresent()
                && reasoningTokens.getAsLong() > outputTokens.getAsLong()) {
            throw new IllegalArgumentException("reasoning usage is invalid");
        }
    }

    public static UsageMeasurement from(ReviewAnalysisMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata is required");
        AiTokenUsage usage = metadata.tokenUsage();
        return new UsageMeasurement(Optional.of(metadata.provider()), Optional.of(metadata.model()),
                usage.inputTokens(), usage.cachedInputTokens(), usage.outputTokens(),
                usage.reasoningTokens(), usage.totalTokens());
    }

    private static Optional<String> safeIdentifier(Optional<String> value, int maximum) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        String identifier = value.orElseThrow();
        return !identifier.isBlank() && identifier.length() <= maximum
                && SAFE_IDENTIFIER.matcher(identifier).matches() ? Optional.of(identifier) : Optional.empty();
    }

    private static OptionalLong safeTokens(OptionalLong value) {
        OptionalLong normalized = value == null ? OptionalLong.empty() : value;
        if (normalized.stream().anyMatch(tokenCount -> tokenCount < 0 || tokenCount > MAXIMUM_TOKEN_COUNT)) {
            throw new IllegalArgumentException("token usage is outside accounting bounds");
        }
        return normalized;
    }

    @Override
    public String toString() {
        return "UsageMeasurement[provider=" + provider.orElse("<unknown>")
                + ", model=" + model.orElse("<unknown>") + ", tokens=<redacted>]";
    }
}
