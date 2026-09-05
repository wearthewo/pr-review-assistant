package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NoOpReviewJobHandlerTest {

    @Test
    void refusesToSilentlyCompleteARealReviewTargetJob() {
        Instant now = Instant.parse("2026-09-05T12:00:00Z");
        ReviewTarget target = new ReviewTarget(1, 2, 3, "a".repeat(40));
        ClaimedReviewJob job = new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 1, 3, now, now.plusSeconds(60), target);

        ReviewJobExecutionResult result = new NoOpReviewJobHandler().handle(job);

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("REVIEW_HANDLER_NOT_IMPLEMENTED");
    }

    @Test
    void retainsM4PlaceholderBehaviorForInfrastructureTests() {
        Instant now = Instant.parse("2026-09-05T12:00:00Z");
        ClaimedReviewJob job = new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 1, 3, now, now.plusSeconds(60));

        assertThat(new NoOpReviewJobHandler().handle(job).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
    }
}
