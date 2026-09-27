package io.prreviewassistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantAuthorizationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-27T12:00:00Z");
    private static final AuthenticatedUserIdentity IDENTITY =
            new AuthenticatedUserIdentity("https://issuer.example/", "sensitive-subject");

    @Test
    void userProvisioningFailureIsClassifiedWithoutExposingItsCause() {
        ApplicationIdentityStore store = mock(ApplicationIdentityStore.class);
        when(store.provisionUser(IDENTITY, NOW)).thenThrow(new IllegalStateException("database-secret"));
        TenantAuthorizationService service = service(store);

        assertThatThrownBy(() -> service.session(IDENTITY))
                .isInstanceOfSatisfying(DashboardSessionFailureException.class, exception -> {
                    assertThat(exception.stage()).isEqualTo(
                            DashboardSessionFailureException.Stage.APPLICATION_USER_PROVISIONING);
                    assertThat(exception.toString()).doesNotContain("database-secret", "sensitive-subject");
                });
    }

    @Test
    void membershipLookupFailureIsClassifiedWithoutExposingItsCause() {
        ApplicationIdentityStore store = mock(ApplicationIdentityStore.class);
        ApplicationUser user = new ApplicationUser(UUID.randomUUID(), NOW, NOW);
        when(store.provisionUser(IDENTITY, NOW)).thenReturn(user);
        when(store.findMemberships(user.id())).thenThrow(new IllegalStateException("membership-secret"));
        TenantAuthorizationService service = service(store);

        assertThatThrownBy(() -> service.session(IDENTITY))
                .isInstanceOfSatisfying(DashboardSessionFailureException.class, exception -> {
                    assertThat(exception.stage()).isEqualTo(
                            DashboardSessionFailureException.Stage.MEMBERSHIP_LOOKUP);
                    assertThat(exception.toString()).doesNotContain("membership-secret", "sensitive-subject");
                });
    }

    private static TenantAuthorizationService service(ApplicationIdentityStore store) {
        return new TenantAuthorizationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
