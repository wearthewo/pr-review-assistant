package io.prreviewassistant.review.analysis;

import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiTokenUsage;

import java.time.Duration;
import java.util.Objects;

public record ReviewAnalysisMetadata(
        String provider,
        String model,
        AiModelTier modelTier,
        AiTokenUsage tokenUsage,
        Duration duration,
        int providerAttemptCeiling) {

    public ReviewAnalysisMetadata {
        Objects.requireNonNull(provider, "provider is required");
        Objects.requireNonNull(model, "model is required");
        Objects.requireNonNull(modelTier, "modelTier is required");
        Objects.requireNonNull(tokenUsage, "tokenUsage is required");
        Objects.requireNonNull(duration, "duration is required");
        if (provider.isBlank() || model.isBlank() || duration.isNegative() || providerAttemptCeiling < 1) {
            throw new IllegalArgumentException("review analysis metadata is invalid");
        }
    }
}
