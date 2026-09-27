package io.prreviewassistant.dashboard;

import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import io.prreviewassistant.identity.DashboardSession;
import io.prreviewassistant.identity.DashboardSessionFailureException;
import io.prreviewassistant.identity.TenantAuthorizationService;
import io.prreviewassistant.identity.TenantMembershipRole;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardSessionController {
    private final TenantAuthorizationService authorizationService;

    public DashboardSessionController(TenantAuthorizationService authorizationService) {
        this.authorizationService = authorizationService;
    }

    @GetMapping("/session")
    public DashboardSessionResponse session(@AuthenticationPrincipal Jwt jwt) {
        AuthenticatedUserIdentity identity;
        try {
            identity = new AuthenticatedUserIdentity(jwt.getIssuer().toString(), jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new DashboardSessionFailureException(
                    DashboardSessionFailureException.Stage.IDENTITY_MAPPING, exception);
        }
        DashboardSession session = authorizationService.session(identity);
        try {
            List<MembershipResponse> memberships = session.memberships().stream()
                    .map(membership -> new MembershipResponse(membership.tenantId(), membership.role()))
                    .toList();
            return new DashboardSessionResponse(session.user().id(), memberships, session.onboardingRequired());
        } catch (RuntimeException exception) {
            throw new DashboardSessionFailureException(
                    DashboardSessionFailureException.Stage.RESPONSE_MAPPING, exception);
        }
    }

    public record DashboardSessionResponse(
            UUID applicationUserId,
            List<MembershipResponse> memberships,
            boolean onboardingRequired) {
        public DashboardSessionResponse {
            memberships = List.copyOf(memberships);
        }
    }

    public record MembershipResponse(UUID tenantId, TenantMembershipRole role) {
    }
}
