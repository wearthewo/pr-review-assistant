package io.prreviewassistant.review.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import io.prreviewassistant.review.publication.PublicationHandoffService;
import io.prreviewassistant.review.publication.PublicationHandoffResult;
import io.prreviewassistant.review.publication.ClaimedPublicationJob;
import io.prreviewassistant.review.publication.PublicationPayload;
import io.prreviewassistant.review.publication.PublicationPayloadCodec;
import io.prreviewassistant.review.publication.PublicationStatus;
import io.prreviewassistant.review.publication.PublicationStore;
import io.prreviewassistant.review.publication.ReviewPublication;
import io.prreviewassistant.review.publication.ReviewPublicationService;
import io.prreviewassistant.review.publication.ReviewPublicationProperties;
import io.prreviewassistant.github.client.GitHubPublishedReview;
import io.prreviewassistant.github.client.GitHubReviewException;
import io.prreviewassistant.github.client.GitHubReviewErrorType;
import io.prreviewassistant.github.client.GitHubReviewPage;
import io.prreviewassistant.github.client.GitHubReviewPublisher;
import io.prreviewassistant.tenant.TenantContext;

class PullRequestRetrievalJobHandlerTest {

    private static final ReviewTarget TARGET = new ReviewTarget(1, 2, 3, "a".repeat(40));
    private static final TenantContext TENANT_CONTEXT = new TenantContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2);

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

    @Test
    void successfulAnalysisHandsOffOnceAndCompletesWithoutPublishingInline() {
        Fixture fixture = fixtureWithEngine();
        PublicationHandoffService handoff = mock(PublicationHandoffService.class);
        PullRequestRetrievalJobHandler handler = new PullRequestRetrievalJobHandler(
                mockLoaderFor(fixture.context().pullRequest()), mockContextBuilderFor(fixture.context()),
                fixture.engine(), fixture.suppressionEngine(), handoff);
        ClaimedReviewJob claim = claim(TARGET);

        ReviewJobExecutionResult result = handler.handle(claim);

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.engine(), org.mockito.Mockito.times(1)).analyze(fixture.context());
        verify(handoff, org.mockito.Mockito.times(1)).handoff(
                org.mockito.ArgumentMatchers.eq(claim.id()),
                org.mockito.ArgumentMatchers.same(TENANT_CONTEXT), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(fixture.context().pullRequest()));
    }

    @Test
    void completedHandoffAfterCrashSkipsRetrievalAndPaidAnalysisOnReclaim() {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        ReviewContextBuilder contextBuilder = mock(ReviewContextBuilder.class);
        ReviewEngine engine = mock(ReviewEngine.class);
        FindingSuppressionEngine suppression = mock(FindingSuppressionEngine.class);
        PublicationHandoffService handoff = mock(PublicationHandoffService.class);
        ClaimedReviewJob claim = claim(TARGET);
        when(handoff.alreadyHandedOff(claim.id())).thenReturn(true);
        PullRequestRetrievalJobHandler handler = new PullRequestRetrievalJobHandler(
                loader, contextBuilder, engine, suppression, handoff);

        ReviewJobExecutionResult result = handler.handle(claim);

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(loader, org.mockito.Mockito.never()).load(org.mockito.ArgumentMatchers.any());
        verify(engine, org.mockito.Mockito.never()).analyze(org.mockito.ArgumentMatchers.any());
        verify(suppression, org.mockito.Mockito.never()).validate(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void disabledPublishingStillCommitsHandoffSoEnablingLaterNeedsNoSecondAiCall() {
        Fixture fixture = fixtureWithEngine();
        PublicationHandoffService handoff = mock(PublicationHandoffService.class);
        when(handoff.handoff(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(PublicationHandoffResult.CREATED);
        PullRequestRetrievalJobHandler handler = new PullRequestRetrievalJobHandler(
                mockLoaderFor(fixture.context().pullRequest()),
                mockContextBuilderFor(fixture.context()),
                fixture.engine(),
                fixture.suppressionEngine(),
                handoff);

        ReviewJobExecutionResult result = handler.handle(claim(TARGET));

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.engine(), org.mockito.Mockito.times(1)).analyze(fixture.context());
        verify(handoff, org.mockito.Mockito.times(1)).handoff(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.same(TENANT_CONTEXT),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.same(fixture.context().pullRequest()));
    }

    @Test
    void ambiguousPublicationRetryNeverRerunsPaidAnalysis() {
        Fixture fixture = fixtureWithEngine();
        PublicationHandoffService handoff = mock(PublicationHandoffService.class);
        PullRequestRetrievalJobHandler handler = new PullRequestRetrievalJobHandler(
                mockLoaderFor(fixture.context().pullRequest()),
                mockContextBuilderFor(fixture.context()),
                fixture.engine(),
                fixture.suppressionEngine(),
                handoff);
        assertThat(handler.handle(claim(TARGET)).outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);

        PublicationStore store = mock(PublicationStore.class);
        GitHubReviewPublisher publisher = mock(GitHubReviewPublisher.class);
        PublicationPayloadCodec codec = new PublicationPayloadCodec(20_000);
        UUID publicationId = UUID.randomUUID();
        String key = "b".repeat(64);
        String encoded = codec.encode(new PublicationPayload(
                1,
                "summary\n<!-- pr-review-assistant:publication:" + key + " -->",
                List.of()));
        ReviewPublication pending = new ReviewPublication(
                publicationId, UUID.randomUUID(), TENANT_CONTEXT.tenantId(), TENANT_CONTEXT.repositoryId(),
                1, 2, "octo", "repo", 3, "a".repeat(40),
                key, 1, 1, encoded, PublicationStatus.PENDING, null, null);
        ReviewPublication ambiguous = new ReviewPublication(
                publicationId, pending.analysisJobId(), TENANT_CONTEXT.tenantId(), TENANT_CONTEXT.repositoryId(),
                1, 2, "octo", "repo", 3, "a".repeat(40),
                key, 1, 1, encoded, PublicationStatus.AMBIGUOUS, null, null);
        when(store.find(publicationId)).thenReturn(Optional.of(pending), Optional.of(ambiguous));
        when(store.markAmbiguous(org.mockito.ArgumentMatchers.eq(publicationId),
                org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(store.markPublished(org.mockito.ArgumentMatchers.eq(publicationId), org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(publisher.create(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(GitHubReviewException.of(GitHubReviewErrorType.AMBIGUOUS_DELIVERY))
                .thenReturn(new GitHubPublishedReview(42, Instant.parse("2026-09-06T12:00:00Z")));
        when(publisher.list(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new GitHubReviewPage(List.of(), false));
        ReviewPublicationProperties properties = new ReviewPublicationProperties(
                true, 6_000, 2_000, 20_000, 10, 3, Duration.ofSeconds(10), Duration.ofMinutes(5),
                Duration.ofSeconds(5), 10, Duration.ofMinutes(2));
        ReviewPublicationService publicationService = new ReviewPublicationService(
                store, codec, publisher, properties,
                Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC));
        ClaimedPublicationJob publicationClaim = new ClaimedPublicationJob(
                UUID.randomUUID(), publicationId, UUID.randomUUID(), 1, 3,
                Instant.parse("2026-09-06T12:00:00Z"), Instant.parse("2026-09-06T12:01:00Z"));

        assertThat(publicationService.publish(publicationClaim).outcome())
                .isEqualTo(io.prreviewassistant.review.publication.PublicationExecutionResult.Outcome.RETRYABLE);
        assertThat(publicationService.publish(publicationClaim).outcome())
                .isEqualTo(io.prreviewassistant.review.publication.PublicationExecutionResult.Outcome.SUCCESS);

        verify(fixture.engine(), org.mockito.Mockito.times(1)).analyze(fixture.context());
        verify(publisher, org.mockito.Mockito.times(2)).create(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void zeroCandidatesOrAllSuppressedCompleteWithoutPublication(int candidateCount) {
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

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        assertThat(result.errorCode()).isNull();
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
        ValidatedReview validated = mock(ValidatedReview.class);
        when(validated.findings()).thenReturn(List.of(mock(io.prreviewassistant.review.analysis.ReviewFinding.class)));
        when(suppressionEngine.validate(analysis, context)).thenReturn(validated);
        return new Fixture(new PullRequestRetrievalJobHandler(
                loader, contextBuilder, engine, suppressionEngine),
                engine, suppressionEngine, analysis, context);
    }

    private PullRequestLoader mockLoaderFor(PullRequestSnapshot snapshot) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenReturn(PullRequestLoadResult.ready(snapshot));
        return loader;
    }

    private ReviewContextBuilder mockContextBuilderFor(ReviewContext context) {
        ReviewContextBuilder builder = mock(ReviewContextBuilder.class);
        when(builder.build(context.pullRequest())).thenReturn(ReviewContextBuildResult.ready(context));
        return builder;
    }

    private record Fixture(PullRequestRetrievalJobHandler handler, ReviewEngine engine,
            FindingSuppressionEngine suppressionEngine, ReviewAnalysis analysis, ReviewContext context) { }

    private ClaimedReviewJob claim(ReviewTarget target) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new ClaimedReviewJob(
                UUID.randomUUID(), UUID.randomUUID(), 1, 3, now, now.plusSeconds(60), target,
                target == null ? null : TENANT_CONTEXT);
    }
}
