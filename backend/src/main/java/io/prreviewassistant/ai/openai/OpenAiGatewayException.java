package io.prreviewassistant.ai.openai;

import io.prreviewassistant.ai.AiProviderErrorType;

final class OpenAiGatewayException extends RuntimeException {
    private final AiProviderErrorType errorType;
    OpenAiGatewayException(AiProviderErrorType errorType, Throwable cause) {
        super("OpenAI request failed: " + errorType.name(), cause);
        this.errorType = errorType;
    }
    AiProviderErrorType errorType() { return errorType; }
}
