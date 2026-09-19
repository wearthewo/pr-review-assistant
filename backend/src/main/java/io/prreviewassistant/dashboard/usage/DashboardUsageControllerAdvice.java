package io.prreviewassistant.dashboard.usage;

import io.prreviewassistant.identity.TenantAccessDeniedException;
import io.prreviewassistant.usage.UsageAccountingException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = DashboardUsageController.class)
public class DashboardUsageControllerAdvice {
    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<Void> accessDenied() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Void> malformedIdentifier() {
        return ResponseEntity.badRequest().build();
    }

    @ExceptionHandler(UsageAccountingException.class)
    ResponseEntity<Void> accountingUnavailable() {
        return ResponseEntity.status(503).build();
    }
}
