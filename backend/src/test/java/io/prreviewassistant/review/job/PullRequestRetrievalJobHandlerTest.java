package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import org.junit.jupiter.api.Test;

class PullRequestRetrievalJobHandlerTest {

    private static final ReviewTarget TARGET = new ReviewTarget(1, 2, 3, "a".repeat(40));

    @Test
    void successfulRetrievalNeverMarksReviewCompletedBeforeAnalysisExists() {
        assertOutcome(PullRequestLoadResult.ready(new PullRequestSnapshot(
                        1, 2, 3, "a".repeat(40), "b".repeat(40), false, List.of())),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "REVIEW_ANALYSIS_NOT_IMPLEMENTED");
    }

    @Test
    void staleAndOversizedPullRequestsAreTerminal() {
        assertOutcome(PullRequestLoadResult.stale(),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "STALE_PULL_REQUEST_REVISION");
        assertOutcome(PullRequestLoadResult.tooLarge(),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "PULL_REQUEST_TOO_LARGE");
    }

    @Test
    void rateLimitServerFailureAndTimeoutClassificationAreRetryable() {
        assertGitHubFailure(GitHubException.rateLimited(),
                ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE, "GITHUB_RATE_LIMITED");
        assertGitHubFailure(GitHubException.transientFailure(),
                ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE, "GITHUB_TRANSIENT_FAILURE");
    }

    @Test
    void inaccessibleAndMalformedPullRequestsAreTerminal() {
        assertGitHubFailure(GitHubException.resourceNotFound(),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "GITHUB_RESOURCE_NOT_ACCESSIBLE");
        assertGitHubFailure(GitHubException.authenticationRejected(),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "GITHUB_AUTHENTICATION_REJECTED");
        assertGitHubFailure(GitHubException.malformedResponse(),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "GITHUB_RESPONSE_INVALID");
    }

    @Test
    void retainsM4TargetlessFixtureBehavior() {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader).handle(claim(null));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
    }

    private void assertOutcome(
            PullRequestLoadResult loadResult,
            ReviewJobExecutionResult.Outcome outcome,
            String errorCode) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenReturn(loadResult);

        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader).handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode().value()).isEqualTo(errorCode);
    }

    private void assertGitHubFailure(
            GitHubException failure,
            ReviewJobExecutionResult.Outcome outcome,
            String errorCode) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenThrow(failure);

        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader).handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode().value()).isEqualTo(errorCode);
        assertThat(result.errorCode().value()).doesNotContain("patch", "token", "secret");
    }

    private ClaimedReviewJob claim(ReviewTarget target) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 1, 3, now, now.plusSeconds(60), target);
    }
}
