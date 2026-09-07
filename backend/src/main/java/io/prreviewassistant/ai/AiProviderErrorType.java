package io.prreviewassistant.ai;

public enum AiProviderErrorType {
    AUTHENTICATION(false), PERMISSION_DENIED(false), RATE_LIMITED(true), TRANSIENT(true),
    TIMEOUT(true), INVALID_REQUEST(false), INPUT_TOO_LARGE(false), OUTPUT_INVALID(false),
    MODEL_UNAVAILABLE(false), PROVIDER_FAILURE(false);

    private final boolean retryable;
    AiProviderErrorType(boolean retryable) { this.retryable = retryable; }
    public boolean retryable() { return retryable; }
}
