package io.prreviewassistant.ai.openai;

import io.prreviewassistant.ai.AiGenerationProfile;
import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiReasoningEffort;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties("review.ai.openai")
@Validated
public record OpenAiProperties(String apiKey, @NotBlank String model,
                               @NotNull AiReasoningEffort reasoningEffort,
                               @Min(1) @Max(AiGenerationProfile.HARD_MAX_OUTPUT_TOKENS) int maxOutputTokens,
                               @NotNull Duration timeout, @Min(0) @Max(1) int maxRetries) {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);

    public OpenAiProperties {
        if (model == null || model.isBlank() || model.length() > 100 || model.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("OpenAI model configuration is invalid");
        }
        if (reasoningEffort == null || maxOutputTokens < 1
                || maxOutputTokens > AiGenerationProfile.HARD_MAX_OUTPUT_TOKENS
                || timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0
                || maxRetries < 0 || maxRetries > 1) {
            throw new IllegalArgumentException("OpenAI generation configuration is invalid");
        }
    }

    public AiGenerationProfile defaultProfile() {
        return new AiGenerationProfile(AiModelTier.BALANCED, reasoningEffort, maxOutputTokens);
    }

    public String requiredApiKey() {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OpenAI API key is required when AI is enabled");
        return apiKey;
    }

    public int maximumAttempts() { return maxRetries + 1; }

    @Override public String toString() {
        return "OpenAiProperties[apiKey=<redacted>, model=" + model + ", reasoningEffort=" + reasoningEffort
                + ", maxOutputTokens=" + maxOutputTokens + ", timeout=" + timeout
                + ", maxRetries=" + maxRetries + "]";
    }
}
