package io.prreviewassistant.ai;

import java.util.Objects;

public record AiGenerationProfile(AiModelTier modelTier, AiReasoningEffort reasoningEffort,
                                  int maxOutputTokens) {
    public static final int HARD_MAX_OUTPUT_TOKENS = 8_192;

    public AiGenerationProfile {
        Objects.requireNonNull(modelTier, "modelTier is required");
        Objects.requireNonNull(reasoningEffort, "reasoningEffort is required");
        if (maxOutputTokens < 1 || maxOutputTokens > HARD_MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException("maxOutputTokens is outside the allowed range");
        }
    }
}
