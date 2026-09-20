package io.prreviewassistant.github.webhook;

import io.prreviewassistant.observability.ApplicationMetrics;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice(assignableTypes = GitHubWebhookController.class)
final class GitHubWebhookControllerAdvice {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubWebhookControllerAdvice.class);

    private final ApplicationMetrics metrics;

    GitHubWebhookControllerAdvice(ApplicationMetrics metrics) {
        this.metrics = metrics;
    }

    GitHubWebhookControllerAdvice() {
        this(ApplicationMetrics.noop());
    }

    @ExceptionHandler(GitHubWebhookException.class)
    ResponseEntity<Void> handleWebhookError(GitHubWebhookException exception) {
        String reason = switch (exception.error()) {
            case UNAUTHORIZED -> "invalid_signature";
            case PAYLOAD_TOO_LARGE -> "oversized";
            case INVALID_REQUEST -> "malformed";
            case INVALID_CONFIGURATION -> "configuration";
        };
        metrics.webhookRejected(reason);
        LOGGER.atWarn().addKeyValue("event", "github_webhook_rejected")
                .addKeyValue("outcome", "rejected")
                .addKeyValue("reason", reason)
                .log("GitHub webhook rejected");
        return ResponseEntity.status(exception.error().status()).build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Void> handleUnsupportedMediaType() {
        metrics.webhookRejected("unsupported_media_type");
        LOGGER.atWarn().addKeyValue("event", "github_webhook_rejected")
                .addKeyValue("outcome", "rejected")
                .addKeyValue("reason", "unsupported_media_type")
                .log("GitHub webhook rejected");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleUnexpectedFailure() {
        metrics.webhookRejected("internal_failure");
        LOGGER.atError().addKeyValue("event", "github_webhook_failed")
                .addKeyValue("outcome", "failure")
                .addKeyValue("reason", "internal_failure")
                .log("GitHub webhook ingestion failed");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
