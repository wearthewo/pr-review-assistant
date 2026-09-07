package io.prreviewassistant.ai;

import java.util.OptionalLong;

public record AiTokenUsage(OptionalLong inputTokens, OptionalLong cachedInputTokens,
                           OptionalLong outputTokens, OptionalLong reasoningTokens,
                           OptionalLong totalTokens) {
    public AiTokenUsage {
        inputTokens = normalize(inputTokens);
        cachedInputTokens = cachedInputTokens == null ? OptionalLong.empty() : cachedInputTokens;
        outputTokens = normalize(outputTokens);
        reasoningTokens = reasoningTokens == null ? OptionalLong.empty() : reasoningTokens;
        totalTokens = normalize(totalTokens);
        if (inputTokens.stream().anyMatch(value -> value < 0)
                || cachedInputTokens.stream().anyMatch(value -> value < 0)
                || outputTokens.stream().anyMatch(value -> value < 0)
                || reasoningTokens.stream().anyMatch(value -> value < 0)
                || totalTokens.stream().anyMatch(value -> value < 0)
                || (inputTokens.isPresent() && cachedInputTokens.isPresent()
                    && cachedInputTokens.getAsLong() > inputTokens.getAsLong())
                || (outputTokens.isPresent() && reasoningTokens.isPresent()
                    && reasoningTokens.getAsLong() > outputTokens.getAsLong())) {
            throw new IllegalArgumentException("token usage values are invalid");
        }
    }

    public static AiTokenUsage unavailable() {
        return new AiTokenUsage(OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty(),
                OptionalLong.empty(), OptionalLong.empty());
    }

    private static OptionalLong normalize(OptionalLong value) {
        return value == null ? OptionalLong.empty() : value;
    }
}
