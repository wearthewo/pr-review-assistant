package io.prreviewassistant.dashboard.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.prreviewassistant.identity.ApplicationIdentityStore;
import io.prreviewassistant.identity.ApplicationUser;
import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitHubConnectionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final UUID USER_ID = UUID.fromString("6e2b4c5d-32fd-4a7a-9dcb-3dc7e6271f05");
    private static final AuthenticatedUserIdentity IDENTITY =
            new AuthenticatedUserIdentity("https://issuer.example/", "subject");

    private final ApplicationIdentityStore identities = mock(ApplicationIdentityStore.class);
    private final MemoryStateStore states = new MemoryStateStore();
    private final GitHubUserAuthorizationClient client = mock(GitHubUserAuthorizationClient.class);
    private final GitHubOwnershipBootstrapStore ownership = mock(GitHubOwnershipBootstrapStore.class);
    private GitHubConnectionService service;

    @BeforeEach
    void setUp() {
        when(identities.provisionUser(any(), any())).thenReturn(new ApplicationUser(USER_ID, NOW, NOW));
        service = new GitHubConnectionService(identities, states, client, ownership, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(new byte[]{1, 2, 3}));
    }

    @Test
    void startCreatesHashedUserBoundExpiringStateAndPkceAuthorizationUrl() {
        String url = service.start(IDENTITY);
        assertThat(url).startsWith("https://github.com/login/oauth/authorize?")
                .contains("client_id=client-id", "redirect_uri=https://app.example/github/callback",
                        "code_challenge_method=S256")
                .doesNotContain(states.verifier);
        assertThat(states.hash).hasSize(64).doesNotContain(states.rawState == null ? "never" : states.rawState);
        assertThat(states.userId).isEqualTo(USER_ID);
        assertThat(states.expiresAt).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void exactVerifiedPersonalInstallationProvisionsMappingAndBindsOwner() {
        states.allow("expected-state", USER_ID, "v".repeat(43));
        when(client.verify("code", "v".repeat(43))).thenReturn(new GitHubConnectionProof(77,
                List.of(new GitHubConnectionProof.AccessibleInstallation(123, 1, 77, "User", "User"))));
        when(ownership.provisionPersonalInstallationOwner(123, USER_ID, NOW))
                .thenReturn(GitHubOwnershipBootstrapStore.MembershipBindingResult.CREATED);

        assertThat(service.complete(IDENTITY, "code", "expected-state"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.CONNECTED);
    }

    @Test
    void organizationAccessAndArbitraryInstallationsCannotCreateOwner() {
        states.allow("state", USER_ID, "v".repeat(43));
        when(client.verify(any(), any())).thenReturn(new GitHubConnectionProof(77, List.of(
                new GitHubConnectionProof.AccessibleInstallation(123, 1, 88, "Organization", "Organization"),
                new GitHubConnectionProof.AccessibleInstallation(999, 2, 77, "User", "User"))));

        assertThat(service.complete(IDENTITY, "code", "state"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.NO_ELIGIBLE_INSTALLATION);
        verifyNoInteractions(ownership);
    }

    @Test
    void installationForDifferentConfiguredAppCannotCreateOwner() {
        states.allow("state", USER_ID, "v".repeat(43));
        when(client.verify(any(), any())).thenReturn(new GitHubConnectionProof(77, List.of(
                new GitHubConnectionProof.AccessibleInstallation(123, 2, 77, "User", "User"))));

        assertThat(service.complete(IDENTITY, "code", "state"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.NO_ELIGIBLE_INSTALLATION);
        verifyNoInteractions(ownership);
    }

    @Test
    void multipleVerifiedMatchingInstallationsFailClosedEvenWhenTheyMapToOneTenant() {
        states.allow("state", USER_ID, "v".repeat(43));
        when(client.verify(any(), any())).thenReturn(new GitHubConnectionProof(77, List.of(
                new GitHubConnectionProof.AccessibleInstallation(123, 1, 77, "User", "User"),
                new GitHubConnectionProof.AccessibleInstallation(124, 1, 77, "User", "User"))));

        assertThat(service.complete(IDENTITY, "code", "state"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.MULTIPLE_INSTALLATIONS_UNSUPPORTED);
    }

    @Test
    void existingMembershipIsIdempotentAndOwnershipConflictFailsClosed() {
        states.allow("first", USER_ID, "v".repeat(43));
        when(client.verify(any(), any())).thenReturn(new GitHubConnectionProof(77,
                List.of(new GitHubConnectionProof.AccessibleInstallation(123, 1, 77, "User", "User"))));
        when(ownership.provisionPersonalInstallationOwner(123, USER_ID, NOW))
                .thenReturn(GitHubOwnershipBootstrapStore.MembershipBindingResult.ALREADY_MEMBER);
        assertThat(service.complete(IDENTITY, "code", "first"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.ALREADY_CONNECTED);

        states.allow("second", USER_ID, "v".repeat(43));
        when(ownership.provisionPersonalInstallationOwner(123, USER_ID, NOW))
                .thenReturn(GitHubOwnershipBootstrapStore.MembershipBindingResult.OWNERSHIP_CONFLICT);
        assertThat(service.complete(IDENTITY, "code", "second"))
                .isEqualTo(GitHubConnectionService.ConnectionResult.OWNERSHIP_CONFLICT);
    }

    @Test
    void invalidCrossUserExpiredOrReusedStateFailsBeforeGitHub() {
        assertThatThrownBy(() -> service.complete(IDENTITY, "code", "unknown"))
                .isInstanceOf(GitHubConnectionException.class)
                .extracting(error -> ((GitHubConnectionException) error).error())
                .isEqualTo(GitHubConnectionError.INVALID_STATE);
    }

    @Test
    void secretBearingConfigurationAndCallbackValuesAreRedacted() {
        assertThat(properties().toString()).isEqualTo("GitHubConnectionProperties[configured=true]")
                .doesNotContain("client-secret");
        assertThat(new GitHubConnectionController.CallbackRequest("secret-code", "secret-state").toString())
                .doesNotContain("secret-code", "secret-state");
        assertThat(new GitHubConnectionController.StartResponse("https://github.com/?state=secret-state").toString())
                .doesNotContain("secret-state");
    }

    private static GitHubConnectionProperties properties() {
        return new GitHubConnectionProperties("1", "client-id", "client-secret",
                URI.create("https://app.example/github/callback"), URI.create("https://github.com"),
                Duration.ofMinutes(10), 5, 10, 1000, 262144);
    }

    private static final class MemoryStateStore implements GitHubConnectionStateStore {
        private String hash;
        private UUID userId;
        private String verifier;
        private Instant expiresAt;
        private String rawState;

        @Override public void create(String hash, UUID userId, String verifier, Instant expiresAt, Instant now,
                int maxActiveStatesPerUser) {
            this.hash = hash; this.userId = userId; this.verifier = verifier; this.expiresAt = expiresAt;
        }
        void allow(String raw, UUID user, String value) {
            rawState = raw; hash = GitHubConnectionService.hash(raw); userId = user; verifier = value;
            expiresAt = NOW.plusSeconds(60);
        }
        @Override public Optional<String> consume(String requestedHash, UUID requestedUser, Instant now) {
            if (requestedHash.equals(hash) && requestedUser.equals(userId) && now.isBefore(expiresAt)) {
                String value = verifier; verifier = null; return Optional.ofNullable(value);
            }
            return Optional.empty();
        }
    }
}
