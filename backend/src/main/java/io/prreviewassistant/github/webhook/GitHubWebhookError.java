package io.prreviewassistant.github.webhook;

import org.springframework.http.HttpStatus;

enum GitHubWebhookError {
    INVALID_CONFIGURATION(HttpStatus.INTERNAL_SERVER_ERROR, "GitHub webhook configuration is invalid"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "GitHub webhook request is invalid"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "GitHub webhook authentication failed"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "GitHub webhook payload is too large");

    private final HttpStatus status;
    private final String message;

    GitHubWebhookError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    HttpStatus status() {
        return status;
    }

    String message() {
        return message;
    }
}
