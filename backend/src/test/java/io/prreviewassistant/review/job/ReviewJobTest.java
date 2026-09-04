package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReviewJobTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Test
    void initialJobStateIsReadyAndUnclaimed() {
        UUID id = UUID.randomUUID();

        ReviewJob job = new ReviewJob(id, ReviewJobStatus.READY, 0, 3, NOW,
                null, null, null, null, null, null, NOW, NOW);

        assertThat(job.id()).isEqualTo(id);
        assertThat(job.status()).isEqualTo(ReviewJobStatus.READY);
        assertThat(job.attempts()).isZero();
        assertThat(job.maxAttempts()).isEqualTo(3);
        assertThat(job.nextAttemptAt()).isEqualTo(NOW);
        assertThat(job.claimToken()).isNull();
    }

    @Test
    void rejectsInvalidAttemptCounts() {
        assertThatThrownBy(() -> job(-1, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> job(4, 3)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorCodesAreBoundedSafeIdentifiersOnly() {
        assertThat(new ReviewJobErrorCode("RATE_LIMITED").toString()).isEqualTo("RATE_LIMITED");
        assertThatThrownBy(() -> new ReviewJobErrorCode("token=secret-value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("secret-value");
        assertThatThrownBy(() -> new ReviewJobErrorCode("A".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimOwnershipTokensAreRedactedFromValueRepresentations() {
        UUID token = UUID.randomUUID();
        ClaimedReviewJob claimed = new ClaimedReviewJob(
                UUID.randomUUID(), token, 1, 3, NOW, NOW.plusSeconds(30));
        ReviewJob job = new ReviewJob(UUID.randomUUID(), ReviewJobStatus.PROCESSING, 1, 3, NOW,
                token, NOW, NOW.plusSeconds(30), null, null, null, NOW, NOW);

        assertThat(claimed.toString()).doesNotContain(token.toString()).contains("<redacted>");
        assertThat(job.toString()).doesNotContain(token.toString()).contains("<redacted>");
    }

    private ReviewJob job(int attempts, int maxAttempts) {
        return new ReviewJob(UUID.randomUUID(), ReviewJobStatus.READY, attempts, maxAttempts, NOW,
                null, null, null, null, null, null, NOW, NOW);
    }
}
