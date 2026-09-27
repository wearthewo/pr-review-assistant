package io.prreviewassistant.dashboard;

import io.prreviewassistant.identity.DashboardSessionFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DashboardSessionController.class)
final class DashboardSessionControllerAdvice {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardSessionControllerAdvice.class);

    @ExceptionHandler(DashboardSessionFailureException.class)
    ResponseEntity<Void> handleSessionFailure(DashboardSessionFailureException exception) {
        logFailure(exception.stage().name());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Void> handleUnexpectedFailure() {
        logFailure("UNEXPECTED");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }

    private static void logFailure(String stage) {
        LOGGER.atError()
                .addKeyValue("event", "dashboard_session_failed")
                .addKeyValue("stage", stage)
                .log("Dashboard session request failed");
    }
}
