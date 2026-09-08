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
import io.prreviewassistant.review.context.*;
import io.prreviewassistant.ai.AiProviderErrorType;
import io.prreviewassistant.ai.AiProviderException;
import io.prreviewassistant.review.analysis.ReviewAnalysisException;
import io.prreviewassistant.review.analysis.FindingSuppressionEngine;
import io.prreviewassistant.review.analysis.ReviewAnalysis;
import io.prreviewassistant.review.analysis.ReviewEngine;
import io.prreviewassistant.review.analysis.SuppressionSummary;
import io.prreviewassistant.review.analysis.ValidatedReview;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.mockito.Mockito.verify;

class PullRequestRetrievalJobHandlerTest {

    private static final ReviewTarget TARGET = new ReviewTarget(1, 2, 3, "a".repeat(40));

    @Test
    void successfulRetrievalNeverMarksReviewCompletedBeforeAnalysisExists() {
        assertOutcome(PullRequestLoadResult.ready(new PullRequestSnapshot(
                        1, 2, 3, "a".repeat(40), "b".repeat(40), false, List.of())),
                ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED");
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
        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader, mock(ReviewContextBuilder.class)).handle(claim(null));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
    }

    @Test
    void enabledAnalysisRunsOnceAndStopsBeforeFalseCompletion() {
        Fixture fixture = fixtureWithEngine();

        ReviewJobExecutionResult result = fixture.handler().handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("REVIEW_PUBLISHING_NOT_IMPLEMENTED");
        verify(fixture.engine()).analyze(fixture.context());
        verify(fixture.suppressionEngine()).validate(
                org.mockito.ArgumentMatchers.same(fixture.analysis()),
                org.mockito.ArgumentMatchers.same(fixture.context()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void zeroCandidatesOrAllSuppressedStillStopAtPublishingBoundary(int candidateCount) {
        Fixture fixture = fixtureWithEngine();
        ValidatedReview validated = mock(ValidatedReview.class);
        when(validated.suppression()).thenReturn(
                new SuppressionSummary(candidateCount, 0, candidateCount,
                        candidateCount == 0 ? java.util.Map.of()
                                : java.util.Map.of(
                                        io.prreviewassistant.review.analysis.SuppressionReason.LOW_CONFIDENCE,
                                        candidateCount)));
        when(fixture.suppressionEngine().validate(fixture.analysis(), fixture.context()))
                .thenReturn(validated);

        ReviewJobExecutionResult result = fixture.handler().handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("REVIEW_PUBLISHING_NOT_IMPLEMENTED");
    }

    @Test
    void retryableProviderFailuresMapToBoundedRetryableCodes() {
        assertAiFailure(AiProviderErrorType.RATE_LIMITED, ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE, "AI_RATE_LIMITED");
        assertAiFailure(AiProviderErrorType.TIMEOUT, ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE, "AI_TIMEOUT");
        assertAiFailure(AiProviderErrorType.TRANSIENT, ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE, "AI_TRANSIENT_FAILURE");
    }

    @Test
    void terminalProviderAndAnalysisFailuresMapToBoundedTerminalCodes() {
        assertAiFailure(AiProviderErrorType.AUTHENTICATION, ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "AI_AUTHENTICATION_FAILED");
        assertAiFailure(AiProviderErrorType.PERMISSION_DENIED, ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "AI_PERMISSION_DENIED");
        assertAiFailure(AiProviderErrorType.MODEL_UNAVAILABLE, ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE, "AI_MODEL_UNAVAILABLE");

        Fixture fixture = fixtureWithEngine();
        when(fixture.engine().analyze(fixture.context())).thenThrow(new ReviewAnalysisException());
        ReviewJobExecutionResult result = fixture.handler().handle(claim(TARGET));
        assertThat(result.errorCode().value()).isEqualTo("AI_INVALID_OUTPUT");
    }

    @Test
    void brokenSuppressionInvariantIsTerminalAndNotRetried() {
        Fixture fixture = fixtureWithEngine();
        when(fixture.suppressionEngine().validate(fixture.analysis(), fixture.context()))
                .thenThrow(new IllegalArgumentException("untrusted finding body"));

        ReviewJobExecutionResult result = fixture.handler().handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("FINDING_SUPPRESSION_INVALID")
                .doesNotContain("untrusted finding body");
    }

    private void assertOutcome(
            PullRequestLoadResult loadResult,
            ReviewJobExecutionResult.Outcome outcome,
            String errorCode) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenReturn(loadResult);

        ReviewContextBuilder contextBuilder = mock(ReviewContextBuilder.class);
        if (loadResult.outcome() == PullRequestLoadResult.Outcome.READY) {
            ReviewContext context = new ReviewContext(TARGET, loadResult.snapshot(), List.of(),
                    new ContextBudgetUsage(0, 0, 0, 0, 0, 0, false));
            when(contextBuilder.build(loadResult.snapshot())).thenReturn(ReviewContextBuildResult.ready(context));
        }
        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader, contextBuilder).handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode().value()).isEqualTo(errorCode);
    }

    private void assertGitHubFailure(
            GitHubException failure,
            ReviewJobExecutionResult.Outcome outcome,
            String errorCode) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenThrow(failure);

        ReviewJobExecutionResult result = new PullRequestRetrievalJobHandler(loader, mock(ReviewContextBuilder.class)).handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode().value()).isEqualTo(errorCode);
        assertThat(result.errorCode().value()).doesNotContain("patch", "token", "secret");
    }

    private void assertAiFailure(AiProviderErrorType type, ReviewJobExecutionResult.Outcome outcome, String code) {
        Fixture fixture = fixtureWithEngine();
        when(fixture.engine().analyze(fixture.context())).thenThrow(new AiProviderException(type));

        ReviewJobExecutionResult result = fixture.handler().handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(outcome);
        assertThat(result.errorCode().value()).isEqualTo(code).doesNotContain("source", "prompt", "token");
    }

    private Fixture fixtureWithEngine() {
        PullRequestSnapshot snapshot = new PullRequestSnapshot(
                1, 2, 3, "a".repeat(40), "b".repeat(40), false, List.of());
        ReviewContext context = new ReviewContext(TARGET, snapshot, List.of(),
                new ContextBudgetUsage(0, 0, 0, 0, 0, 0, false));
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenReturn(PullRequestLoadResult.ready(snapshot));
        ReviewContextBuilder contextBuilder = mock(ReviewContextBuilder.class);
        when(contextBuilder.build(snapshot)).thenReturn(ReviewContextBuildResult.ready(context));
        ReviewEngine engine = mock(ReviewEngine.class);
        FindingSuppressionEngine suppressionEngine = mock(FindingSuppressionEngine.class);
        ReviewAnalysis analysis = mock(ReviewAnalysis.class);
        when(engine.analyze(context)).thenReturn(analysis);
        return new Fixture(new PullRequestRetrievalJobHandler(
                loader, contextBuilder, engine, suppressionEngine),
                engine, suppressionEngine, analysis, context);
    }

    private record Fixture(PullRequestRetrievalJobHandler handler, ReviewEngine engine,
            FindingSuppressionEngine suppressionEngine, ReviewAnalysis analysis, ReviewContext context) { }

    private ClaimedReviewJob claim(ReviewTarget target) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 1, 3, now, now.plusSeconds(60), target);
    }
}
