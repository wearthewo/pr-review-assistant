package io.prreviewassistant.ai;

import java.util.Objects;

public final class AiProviderException extends RuntimeException {
    private final AiProviderErrorType errorType;

    public AiProviderException(AiProviderErrorType errorType) {
        super("AI provider request failed: " + Objects.requireNonNull(errorType, "errorType is required").name());
        this.errorType = errorType;
    }

    public AiProviderErrorType errorType() { return errorType; }
    public boolean retryable() { return errorType.retryable(); }
}
