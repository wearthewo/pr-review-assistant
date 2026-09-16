package io.prreviewassistant.dashboard.github;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.prreviewassistant.identity.ApplicationIdentityStore;
import io.prreviewassistant.identity.ApplicationUser;
import io.prreviewassistant.identity.AuthenticatedUserIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GitHubConnectionService {
    private static final int MAX_CALLBACK_VALUE_LENGTH = 512;

    private final ApplicationIdentityStore identities;
    private final GitHubConnectionStateStore states;
    private final GitHubUserAuthorizationClient client;
    private final GitHubOwnershipBootstrapStore ownership;
    private final GitHubConnectionProperties properties;
    private final Clock clock;
    private final SecureRandom random;

    @Autowired
    public GitHubConnectionService(
            ApplicationIdentityStore identities,
            GitHubConnectionStateStore states,
            GitHubUserAuthorizationClient client,
            GitHubOwnershipBootstrapStore ownership,
            GitHubConnectionProperties properties,
            Clock clock) {
        this(identities, states, client, ownership, properties, clock, new SecureRandom());
    }

    GitHubConnectionService(
            ApplicationIdentityStore identities,
            GitHubConnectionStateStore states,
            GitHubUserAuthorizationClient client,
            GitHubOwnershipBootstrapStore ownership,
            GitHubConnectionProperties properties,
            Clock clock,
            SecureRandom random) {
        this.identities = identities;
        this.states = states;
        this.client = client;
        this.ownership = ownership;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    public String start(AuthenticatedUserIdentity identity) {
        requireConfigured();
        Instant now = clock.instant();
        ApplicationUser user = identities.provisionUser(Objects.requireNonNull(identity), now);
        String state = randomValue(32);
        String verifier = randomValue(64);
        states.create(hash(state), user.id(), verifier, now.plus(properties.stateTtl()), now);
        String challenge = base64Url(digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return UriComponentsBuilder.fromUri(properties.oauthBaseUrl())
                .path("/login/oauth/authorize")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.callbackUrl())
                .queryParam("state", state)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256")
                .build().encode().toUriString();
    }

    public ConnectionResult complete(AuthenticatedUserIdentity identity, String code, String state) {
        requireConfigured();
        if (!bounded(code) || !bounded(state)) {
            throw new GitHubConnectionException(GitHubConnectionError.INVALID_STATE);
        }
        Instant now = clock.instant();
        ApplicationUser user = identities.provisionUser(Objects.requireNonNull(identity), now);
        String verifier = states.consume(hash(state), user.id(), now)
                .orElseThrow(() -> new GitHubConnectionException(GitHubConnectionError.INVALID_STATE));
        GitHubConnectionProof proof = client.verify(code, verifier);

        List<UUID> candidates = new ArrayList<>(2);
        for (GitHubConnectionProof.AccessibleInstallation installation : proof.installations()) {
            if (installation.isOwnedUserInstallation(proof.userId())) {
                ownership.findTenantForInstallation(installation.installationId()).ifPresent(tenantId -> {
                    candidates.add(tenantId);
                });
            }
            if (candidates.size() > 1) {
                return ConnectionResult.MULTIPLE_INSTALLATIONS_UNSUPPORTED;
            }
        }
        if (candidates.isEmpty()) {
            return ConnectionResult.NO_ELIGIBLE_INSTALLATION;
        }
        return switch (ownership.bindOwner(candidates.getFirst(), user.id(), now)) {
            case CREATED -> ConnectionResult.CONNECTED;
            case ALREADY_MEMBER -> ConnectionResult.ALREADY_CONNECTED;
            case OWNERSHIP_CONFLICT -> ConnectionResult.OWNERSHIP_CONFLICT;
        };
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new GitHubConnectionException(GitHubConnectionError.NOT_CONFIGURED);
        }
    }

    private String randomValue(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return base64Url(value);
    }

    private static boolean bounded(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_CALLBACK_VALUE_LENGTH
                && value.chars().noneMatch(character -> Character.isISOControl(character));
    }

    static String hash(String value) {
        return HexFormat.of().formatHex(digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public enum ConnectionResult {
        CONNECTED,
        ALREADY_CONNECTED,
        NO_ELIGIBLE_INSTALLATION,
        MULTIPLE_INSTALLATIONS_UNSUPPORTED,
        OWNERSHIP_CONFLICT
    }
}
