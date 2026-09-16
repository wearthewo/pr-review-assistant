package io.prreviewassistant.dashboard.review;

import io.prreviewassistant.identity.TenantAccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = DashboardReviewController.class)
final class DashboardReviewControllerAdvice {
    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler({DashboardReviewRequestException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Void> invalidRequest() {
        return ResponseEntity.badRequest().build();
    }
}
