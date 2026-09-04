package io.prreviewassistant.review.job;

import java.util.Objects;

public record ReviewJobExecutionResult(Outcome outcome, ReviewJobErrorCode errorCode) {

    public ReviewJobExecutionResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((outcome == Outcome.SUCCESS) != (errorCode == null)) {
            throw new IllegalArgumentException("success must not have an error code and failure must have one");
        }
    }

    public static ReviewJobExecutionResult success() {
        return new ReviewJobExecutionResult(Outcome.SUCCESS, null);
    }

    public static ReviewJobExecutionResult retryable(String safeErrorCode) {
        return new ReviewJobExecutionResult(Outcome.RETRYABLE_FAILURE, new ReviewJobErrorCode(safeErrorCode));
    }

    public static ReviewJobExecutionResult terminal(String safeErrorCode) {
        return new ReviewJobExecutionResult(Outcome.TERMINAL_FAILURE, new ReviewJobErrorCode(safeErrorCode));
    }

    public enum Outcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        TERMINAL_FAILURE
    }
}
