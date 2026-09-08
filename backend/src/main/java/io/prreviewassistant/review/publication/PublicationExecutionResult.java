package io.prreviewassistant.review.publication;

public record PublicationExecutionResult(Outcome outcome, String errorCode) {
    public PublicationExecutionResult {
        if (outcome == null || (outcome == Outcome.SUCCESS) != (errorCode == null)
                || (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,63}"))) {
            throw new IllegalArgumentException("publication execution result is invalid");
        }
    }
    public static PublicationExecutionResult success() { return new PublicationExecutionResult(Outcome.SUCCESS, null); }
    public static PublicationExecutionResult retryable(String code) { return new PublicationExecutionResult(Outcome.RETRYABLE, code); }
    public static PublicationExecutionResult terminal(String code) { return new PublicationExecutionResult(Outcome.TERMINAL, code); }
    public enum Outcome { SUCCESS, RETRYABLE, TERMINAL }
}
