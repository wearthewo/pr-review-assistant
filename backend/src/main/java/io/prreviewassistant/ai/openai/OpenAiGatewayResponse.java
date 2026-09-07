package io.prreviewassistant.ai.openai;

record OpenAiGatewayResponse(String structuredOutput, String requestId, Long inputTokens,
                             Long cachedInputTokens, Long outputTokens, Long reasoningTokens,
                             Long totalTokens) {
    @Override public String toString() { return "OpenAiGatewayResponse[structuredOutput=<redacted>, usage=<present>]"; }
}
