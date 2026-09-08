package io.prreviewassistant.github.client;

public enum GitHubReviewErrorType {
    AMBIGUOUS_DELIVERY,
    RATE_LIMITED,
    TRANSIENT,
    AUTHENTICATION,
    PERMISSION,
    NOT_FOUND,
    INVALID_REVIEW,
    MALFORMED_RESPONSE,
    RESPONSE_TOO_LARGE
}
