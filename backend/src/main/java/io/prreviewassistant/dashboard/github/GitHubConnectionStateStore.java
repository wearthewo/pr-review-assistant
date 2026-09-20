package io.prreviewassistant.dashboard.github;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GitHubConnectionStateStore {
    void create(String stateHash, UUID applicationUserId, String pkceVerifier, Instant expiresAt, Instant now,
            int maxActiveStatesPerUser);

    Optional<String> consume(String stateHash, UUID applicationUserId, Instant now);
}
