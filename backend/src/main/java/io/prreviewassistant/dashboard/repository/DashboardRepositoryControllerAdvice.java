package io.prreviewassistant.dashboard.repository;

import io.prreviewassistant.identity.TenantAccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = DashboardRepositoryController.class)
public class DashboardRepositoryControllerAdvice {
    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Void> malformedIdentifier() {
        return ResponseEntity.badRequest().build();
    }
}
