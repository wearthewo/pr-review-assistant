package io.prreviewassistant.github.webhook;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GitHubWebhookController.class)
final class GitHubWebhookControllerAdvice {

    @ExceptionHandler(GitHubWebhookException.class)
    ResponseEntity<Void> handleWebhookError(GitHubWebhookException exception) {
        return ResponseEntity.status(exception.error().status()).build();
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Void> handleUnsupportedMediaType() {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleUnexpectedFailure() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
}
