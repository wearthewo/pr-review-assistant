package io.prreviewassistant.dashboard.review;

import java.util.UUID;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/tenants/{tenantId}/reviews")
public final class DashboardReviewController {
    private final DashboardReviewService service;

    DashboardReviewController(DashboardReviewService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardReviewService.ReviewPage list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(
                new AuthenticatedUserIdentity(jwt.getIssuer().toString(), jwt.getSubject()),
                tenantId, cursor, limit);
    }
}
