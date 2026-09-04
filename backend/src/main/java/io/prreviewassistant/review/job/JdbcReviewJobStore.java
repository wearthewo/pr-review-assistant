package io.prreviewassistant.review.job;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcReviewJobStore implements ReviewJobStore {

    private static final ReviewJobErrorCode LEASE_EXHAUSTED =
            new ReviewJobErrorCode("LEASE_EXPIRED_MAX_ATTEMPTS");

    private final JdbcClient jdbcClient;
    private final ReviewJobIdentifiers identifiers;

    public JdbcReviewJobStore(JdbcClient jdbcClient, ReviewJobIdentifiers identifiers) {
        this.jdbcClient = jdbcClient;
        this.identifiers = identifiers;
    }

    @Override
    @Transactional
    public ReviewJob create(int maxAttempts, Instant now) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        UUID id = identifiers.newJobId();
        OffsetDateTime timestamp = utc(now);
        jdbcClient.sql("""
                        INSERT INTO review_jobs
                            (id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at)
                        VALUES (:id, 'READY', 0, :maxAttempts, :now, :now, :now)
                        """)
                .param("id", id)
                .param("maxAttempts", maxAttempts)
                .param("now", timestamp)
                .update();
        return new ReviewJob(id, ReviewJobStatus.READY, 0, maxAttempts, now,
                null, null, null, null, null, null, now, now);
    }

    @Override
    @Transactional
    public List<ClaimedReviewJob> claimDue(Instant now, Duration leaseDuration, int batchSize) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }

        OffsetDateTime timestamp = utc(now);
        expireExhaustedClaims(timestamp, batchSize);
        List<ClaimCandidate> candidates = jdbcClient.sql("""
                        SELECT id, attempts, max_attempts
                        FROM review_jobs
                        WHERE (status = 'READY' AND next_attempt_at <= :now)
                           OR (status = 'PROCESSING'
                               AND claim_expires_at <= :now
                               AND attempts < max_attempts)
                        ORDER BY next_attempt_at, created_at, id
                        FOR UPDATE SKIP LOCKED
                        LIMIT :batchSize
                        """)
                .param("now", timestamp)
                .param("batchSize", batchSize)
                .query((resultSet, rowNumber) -> new ClaimCandidate(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getInt("attempts"),
                        resultSet.getInt("max_attempts")))
                .list();

        Instant expiresAt = now.plus(leaseDuration);
        List<ClaimedReviewJob> claims = new ArrayList<>(candidates.size());
        for (ClaimCandidate candidate : candidates) {
            UUID claimToken = identifiers.newClaimToken();
            int attempt = candidate.attempts() + 1;
            jdbcClient.sql("""
                            UPDATE review_jobs
                            SET status = 'PROCESSING',
                                attempts = :attempt,
                                claim_token = :claimToken,
                                claimed_at = :now,
                                claim_expires_at = :expiresAt,
                                updated_at = :now
                            WHERE id = :id
                            """)
                    .param("attempt", attempt)
                    .param("claimToken", claimToken)
                    .param("now", timestamp)
                    .param("expiresAt", utc(expiresAt))
                    .param("id", candidate.id())
                    .update();
            claims.add(new ClaimedReviewJob(candidate.id(), claimToken, attempt,
                    candidate.maxAttempts(), now, expiresAt));
        }
        return List.copyOf(claims);
    }

    @Override
    @Transactional
    public boolean complete(UUID jobId, UUID claimToken, Instant now) {
        return jdbcClient.sql("""
                        UPDATE review_jobs
                        SET status = 'COMPLETED',
                            next_attempt_at = NULL,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            completed_at = :now,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'PROCESSING'
                          AND claim_token = :claimToken
                        """)
                .param("now", utc(now))
                .param("id", jobId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean retry(
            UUID jobId,
            UUID claimToken,
            Instant nextAttemptAt,
            ReviewJobErrorCode errorCode,
            Instant now) {
        return jdbcClient.sql("""
                        UPDATE review_jobs
                        SET status = 'READY',
                            next_attempt_at = :nextAttemptAt,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            last_error_code = :errorCode,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'PROCESSING'
                          AND claim_token = :claimToken
                          AND attempts < max_attempts
                        """)
                .param("nextAttemptAt", utc(nextAttemptAt))
                .param("errorCode", errorCode.value())
                .param("now", utc(now))
                .param("id", jobId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    @Override
    @Transactional
    public boolean fail(UUID jobId, UUID claimToken, ReviewJobErrorCode errorCode, Instant now) {
        return jdbcClient.sql("""
                        UPDATE review_jobs
                        SET status = 'FAILED',
                            next_attempt_at = NULL,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            failed_at = :now,
                            last_error_code = :errorCode,
                            updated_at = :now
                        WHERE id = :id
                          AND status = 'PROCESSING'
                          AND claim_token = :claimToken
                        """)
                .param("now", utc(now))
                .param("errorCode", errorCode.value())
                .param("id", jobId)
                .param("claimToken", claimToken)
                .update() == 1;
    }

    private void expireExhaustedClaims(OffsetDateTime now, int batchSize) {
        jdbcClient.sql("""
                        UPDATE review_jobs
                        SET status = 'FAILED',
                            next_attempt_at = NULL,
                            claim_token = NULL,
                            claimed_at = NULL,
                            claim_expires_at = NULL,
                            failed_at = :now,
                            last_error_code = :errorCode,
                            updated_at = :now
                        WHERE id IN (
                            SELECT id
                            FROM review_jobs
                            WHERE status = 'PROCESSING'
                              AND claim_expires_at <= :now
                              AND attempts >= max_attempts
                            ORDER BY claim_expires_at, created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batchSize
                        )
                        """)
                .param("now", now)
                .param("errorCode", LEASE_EXHAUSTED.value())
                .param("batchSize", batchSize)
                .update();
    }

    private OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ClaimCandidate(UUID id, int attempts, int maxAttempts) {
    }
}
