package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.JsonNode;
import io.prreviewassistant.ai.AiReasoningEffort;

record OpenAiInvocation(String model, AiReasoningEffort reasoningEffort, int maxOutputTokens,
                        String instructions, String input, String schemaName, JsonNode schema, boolean strict) {
    @Override public String toString() {
        return "OpenAiInvocation[model=" + model + ", reasoningEffort=" + reasoningEffort
                + ", maxOutputTokens=" + maxOutputTokens + ", instructions=<redacted>, input=<redacted>, schema=<redacted>]";
    }
}
