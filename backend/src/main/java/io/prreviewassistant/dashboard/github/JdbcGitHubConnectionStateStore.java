package io.prreviewassistant.dashboard.github;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcGitHubConnectionStateStore implements GitHubConnectionStateStore {
    private final JdbcClient jdbc;

    public JdbcGitHubConnectionStateStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void create(String stateHash, UUID applicationUserId, String pkceVerifier, Instant expiresAt, Instant now) {
        jdbc.sql("DELETE FROM github_connection_states WHERE expires_at < :cutoff OR consumed_at < :cutoff")
                .param("cutoff", utc(now.minusSeconds(3600))).update();
        jdbc.sql("""
                INSERT INTO github_connection_states
                  (state_hash, application_user_id, pkce_verifier, expires_at, consumed_at, created_at)
                VALUES (:hash, :userId, :verifier, :expiresAt, NULL, :now)
                """).param("hash", stateHash).param("userId", applicationUserId)
                .param("verifier", pkceVerifier).param("expiresAt", utc(expiresAt))
                .param("now", utc(now)).update();
    }

    @Override
    @Transactional
    public Optional<String> consume(String stateHash, UUID applicationUserId, Instant now) {
        return jdbc.sql("""
                UPDATE github_connection_states
                SET consumed_at = :now
                WHERE state_hash = :hash
                  AND application_user_id = :userId
                  AND consumed_at IS NULL
                  AND expires_at > :now
                RETURNING pkce_verifier
                """).param("hash", stateHash).param("userId", applicationUserId).param("now", utc(now))
                .query(String.class).optional();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
