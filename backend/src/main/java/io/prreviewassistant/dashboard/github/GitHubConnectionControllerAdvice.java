package io.prreviewassistant.dashboard.github;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GitHubConnectionController.class)
public class GitHubConnectionControllerAdvice {
    @ExceptionHandler(GitHubConnectionException.class)
    ResponseEntity<ErrorResponse> connectionError(GitHubConnectionException exception) {
        HttpStatus status = switch (exception.error()) {
            case INVALID_STATE -> HttpStatus.BAD_REQUEST;
            case OWNERSHIP_CONFLICT -> HttpStatus.CONFLICT;
            case NOT_CONFIGURED -> HttpStatus.SERVICE_UNAVAILABLE;
            case GITHUB_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(exception.error()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> invalidInput() {
        return ResponseEntity.badRequest().body(new ErrorResponse(GitHubConnectionError.INVALID_STATE));
    }

    public record ErrorResponse(GitHubConnectionError error) { }
}
