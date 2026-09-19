package io.prreviewassistant.dashboard.usage;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/tenants/{tenantId}/usage")
public class DashboardUsageController {
    private final DashboardUsageService service;

    public DashboardUsageController(DashboardUsageService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardUsageService.UsageResponse current(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId) {
        return service.current(
                new AuthenticatedUserIdentity(jwt.getIssuer().toString(), jwt.getSubject()), tenantId);
    }
}
