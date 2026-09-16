package io.prreviewassistant.dashboard.repository;

import java.util.UUID;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard/tenants/{tenantId}/repositories")
public class DashboardRepositoryController {
    private final DashboardRepositoryService service;

    public DashboardRepositoryController(DashboardRepositoryService service) {
        this.service = service;
    }

    @GetMapping
    public DashboardRepositoryService.RepositoryPage list(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID tenantId) {
        return service.list(new AuthenticatedUserIdentity(jwt.getIssuer().toString(), jwt.getSubject()), tenantId);
    }
}
