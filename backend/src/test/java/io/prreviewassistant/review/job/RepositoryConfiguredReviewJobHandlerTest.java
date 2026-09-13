package io.prreviewassistant.review.job;

import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.review.analysis.FindingSuppressionEngine;
import io.prreviewassistant.review.analysis.ReviewAnalysis;
import io.prreviewassistant.review.analysis.ReviewEngine;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.analysis.ValidatedReview;
import io.prreviewassistant.review.config.EffectiveRepositoryReviewConfig;
import io.prreviewassistant.review.config.RepositoryConfigLoadResult;
import io.prreviewassistant.review.config.RepositoryConfigLoader;
import io.prreviewassistant.review.config.RepositoryConfigStatus;
import io.prreviewassistant.review.config.ReviewMode;
import io.prreviewassistant.review.context.ContextBudgetUsage;
import io.prreviewassistant.review.context.ReviewContext;
import io.prreviewassistant.review.context.ReviewContextBuildResult;
import io.prreviewassistant.review.context.ReviewContextBuilder;
import io.prreviewassistant.review.publication.PublicationHandoffService;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.ChangedFileStatus;
import io.prreviewassistant.review.retrieval.PatchAvailability;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import io.prreviewassistant.tenant.TenantContext;
import io.prreviewassistant.usage.QuotaDecision;
import io.prreviewassistant.usage.UsageAccountingService;
import io.prreviewassistant.usage.UsagePeriod;
import io.prreviewassistant.usage.UsageReservationResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RepositoryConfiguredReviewJobHandlerTest {
    private static final ReviewTarget TARGET = new ReviewTarget(1, 2, 3, "a".repeat(40));
    private static final TenantContext TENANT_CONTEXT = new TenantContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2);

    @Test
    void configOnlyPullRequestLoadsConfigThenCompletesWithoutContextAiOrPublication() {
        var fixture = fixture(snapshot(file(".reviewbot.yml")));

        var result = fixture.handler.handle(claim());

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.configLoader).load(any());
        verify(fixture.contextBuilder, never()).build(any(), any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void allCategoriesDisabledCompletesWithoutContextAiOrPublication() {
        var config = new EffectiveRepositoryReviewConfig(ReviewMode.BALANCED,
                List.of(), Set.of());
        var fixture = fixture(snapshot(file("src/A.java")), config);

        assertThat(fixture.handler.handle(claim()).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.contextBuilder, never()).build(any(), any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void allFilesIgnoredCompletesWithoutContextAiOrPublication() {
        var config = new EffectiveRepositoryReviewConfig(ReviewMode.BALANCED,
                List.of("src/**"), EnumSet.allOf(ReviewFindingCategory.class));
        var fixture = fixture(snapshot(file("src/A.java")), config);

        assertThat(fixture.handler.handle(claim()).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.contextBuilder, never()).build(any(), any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void ignorePolicyFiltersBeforeContextAndZeroPublishedFindingsStillConsumeUsage() {
        var categories = EnumSet.of(ReviewFindingCategory.CORRECTNESS, ReviewFindingCategory.SECURITY);
        var config = new EffectiveRepositoryReviewConfig(
                ReviewMode.FAST, List.of("generated/**", "*.lock"), categories);
        var original = snapshot(file("src/A.java"), file("generated/B.java"), file("package.lock"));
        var fixture = fixture(original, config);
        ArgumentCaptor<PullRequestSnapshot> filtered = ArgumentCaptor.forClass(PullRequestSnapshot.class);
        ReviewContext context = new ReviewContext(TARGET, config.filter(original), List.of(),
                new ContextBudgetUsage(0, 0, 0, 0, 0, 0, false));
        when(fixture.contextBuilder.build(filtered.capture(), org.mockito.ArgumentMatchers.same(config)))
                .thenReturn(ReviewContextBuildResult.ready(context));
        ReviewAnalysis analysis = mock(ReviewAnalysis.class);
        when(analysis.metadata()).thenReturn(mock(
                io.prreviewassistant.review.analysis.ReviewAnalysisMetadata.class));
        when(fixture.reviewEngine.analyze(context, categories)).thenReturn(analysis);
        ValidatedReview validated = mock(ValidatedReview.class);
        when(validated.findings()).thenReturn(List.of());
        when(fixture.suppression.validate(analysis, context)).thenReturn(validated);

        assertThat(fixture.handler.handle(claim()).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        assertThat(filtered.getValue().changedFiles()).extracting(ChangedFile::path)
                .containsExactly("src/A.java");
        verify(fixture.reviewEngine).analyze(context, categories);
        verify(fixture.usageAccounting).reserve(any(), any());
        verify(fixture.usageAccounting).consume(any(), any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
    }

    @Test
    void transientConfigFetchUsesExistingDurableRetryClassification() {
        var fixture = fixture(snapshot(file("src/A.java")));
        when(fixture.configLoader.load(any())).thenThrow(GitHubException.transientFailure());

        var result = fixture.handler.handle(claim());

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("GITHUB_TRANSIENT_FAILURE");
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void completedPublicationHandoffPreventsConfigRefetchAndReanalysis() {
        var fixture = fixture(snapshot(file("src/A.java")));
        when(fixture.publication.alreadyHandedOff(any())).thenReturn(true);

        assertThat(fixture.handler.handle(claim()).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.loader, never()).load(any());
        verify(fixture.configLoader, never()).load(any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void mismatchedTenantOwnershipStopsBeforeGitHubAiAndPublication() {
        var fixture = fixture(snapshot(file("src/A.java")));
        Instant now = Instant.parse("2026-09-09T12:00:00Z");
        TenantContext mismatched = new TenantContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 99, 2);
        ClaimedReviewJob claim = new ClaimedReviewJob(UUID.randomUUID(), UUID.randomUUID(), 1, 3,
                now, now.plusSeconds(60), TARGET, mismatched);

        ReviewJobExecutionResult result = fixture.handler.handle(claim);

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("TENANT_REPOSITORY_OWNERSHIP_MISMATCH");
        verify(fixture.loader, never()).load(any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
        verifyNoInteractions(fixture.usageAccounting);
    }

    @Test
    void quotaDenialAndExistingReservationStopBeforeAiAndPublication() {
        PullRequestSnapshot pullRequest = snapshot(file("src/A.java"));
        var denied = fixture(pullRequest);
        prepareAnalysisContext(denied, pullRequest);
        when(denied.usageAccounting.reserve(any(), any())).thenReturn(reservation(
                UsageReservationResult.Outcome.QUOTA_EXCEEDED, false, 3, 3));

        ReviewJobExecutionResult deniedResult = denied.handler.handle(claim());

        assertThat(deniedResult.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(deniedResult.errorCode().value()).isEqualTo("USAGE_QUOTA_EXCEEDED");
        verify(denied.reviewEngine, never()).analyze(any(), any());
        verify(denied.publication, never()).handoff(any(), any(), any(), any());

        var ambiguous = fixture(pullRequest);
        prepareAnalysisContext(ambiguous, pullRequest);
        when(ambiguous.usageAccounting.reserve(any(), any())).thenReturn(reservation(
                UsageReservationResult.Outcome.EXISTING_RESERVED, false, 3, 3));

        ReviewJobExecutionResult ambiguousResult = ambiguous.handler.handle(claim());

        assertThat(ambiguousResult.errorCode().value()).isEqualTo("USAGE_RESERVATION_AMBIGUOUS");
        verify(ambiguous.reviewEngine, never()).analyze(any(), any());
        verify(ambiguous.publication, never()).handoff(any(), any(), any(), any());
    }

    @Test
    void ambiguousProviderFailureKeepsReservationAndRetryDoesNotInvokeAiAgain() {
        PullRequestSnapshot pullRequest = snapshot(file("src/A.java"));
        var fixture = fixture(pullRequest);
        prepareAnalysisContext(fixture, pullRequest);
        when(fixture.reviewEngine.analyze(any(), any()))
                .thenThrow(new io.prreviewassistant.ai.AiProviderException(
                        io.prreviewassistant.ai.AiProviderErrorType.TIMEOUT));
        ClaimedReviewJob job = claim();

        ReviewJobExecutionResult first = fixture.handler.handle(job);
        when(fixture.usageAccounting.reserve(any(), any())).thenReturn(reservation(
                UsageReservationResult.Outcome.EXISTING_RESERVED, false, 50, 1));
        ReviewJobExecutionResult retry = fixture.handler.handle(job);

        assertThat(first.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE);
        assertThat(first.errorCode().value()).isEqualTo("AI_TIMEOUT");
        assertThat(retry.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(retry.errorCode().value()).isEqualTo("USAGE_RESERVATION_AMBIGUOUS");
        verify(fixture.reviewEngine, org.mockito.Mockito.times(1)).analyze(any(), any());
        verify(fixture.usageAccounting, never()).consume(any(), any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
    }

    @Test
    void postProviderValidationFailureStillFinalizesReportedConsumption() {
        PullRequestSnapshot pullRequest = snapshot(file("src/A.java"));
        var fixture = fixture(pullRequest);
        prepareAnalysisContext(fixture, pullRequest);
        var metadata = mock(io.prreviewassistant.review.analysis.ReviewAnalysisMetadata.class);
        when(fixture.reviewEngine.analyze(any(), any()))
                .thenThrow(new io.prreviewassistant.review.analysis.ReviewAnalysisException(metadata));
        ClaimedReviewJob job = claim();

        ReviewJobExecutionResult result = fixture.handler.handle(job);

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.TERMINAL_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("AI_INVALID_OUTPUT");
        verify(fixture.usageAccounting).consume(job.tenantContext(), job.id(), metadata);
        verify(fixture.publication, never()).handoff(any(), any(), any(), any());
    }

    private void prepareAnalysisContext(Fixture fixture, PullRequestSnapshot snapshot) {
        ReviewContext context = new ReviewContext(TARGET, snapshot, List.of(),
                new ContextBudgetUsage(0, 0, 0, 0, 0, 0, false));
        when(fixture.contextBuilder.build(any(), any())).thenReturn(ReviewContextBuildResult.ready(context));
    }

    private Fixture fixture(PullRequestSnapshot snapshot) {
        return fixture(snapshot, EffectiveRepositoryReviewConfig.defaults());
    }

    private Fixture fixture(PullRequestSnapshot snapshot, EffectiveRepositoryReviewConfig config) {
        PullRequestLoader loader = mock(PullRequestLoader.class);
        when(loader.load(TARGET)).thenReturn(PullRequestLoadResult.ready(snapshot));
        RepositoryConfigLoader configLoader = mock(RepositoryConfigLoader.class);
        when(configLoader.load(snapshot)).thenReturn(
                new RepositoryConfigLoadResult(RepositoryConfigStatus.VALID, config));
        ReviewContextBuilder contextBuilder = mock(ReviewContextBuilder.class);
        ReviewEngine reviewEngine = mock(ReviewEngine.class);
        FindingSuppressionEngine suppression = mock(FindingSuppressionEngine.class);
        PublicationHandoffService publication = mock(PublicationHandoffService.class);
        UsageAccountingService usageAccounting = mock(UsageAccountingService.class);
        when(usageAccounting.reserve(any(), any())).thenReturn(reservation(
                UsageReservationResult.Outcome.ACQUIRED, true, 50, 0));
        return new Fixture(new PullRequestRetrievalJobHandler(loader, contextBuilder, reviewEngine,
                suppression, publication, configLoader, usageAccounting), loader, configLoader, contextBuilder,
                reviewEngine, suppression, publication, usageAccounting);
    }

    private PullRequestSnapshot snapshot(ChangedFile... files) {
        return new PullRequestSnapshot(1, 2, "owner", "repo", 3,
                "a".repeat(40), "b".repeat(40), false, List.of(files));
    }

    private ChangedFile file(String path) {
        return new ChangedFile(path, null, ChangedFileStatus.MODIFIED, 1, 0, 1,
                PatchAvailability.AVAILABLE, "@@ -1 +1 @@\n+x");
    }

    private ClaimedReviewJob claim() {
        Instant now = Instant.parse("2026-09-09T12:00:00Z");
        return new ClaimedReviewJob(UUID.randomUUID(), UUID.randomUUID(), 1, 3,
                now, now.plusSeconds(60), TARGET, TENANT_CONTEXT);
    }

    private record Fixture(
            PullRequestRetrievalJobHandler handler,
            PullRequestLoader loader,
            RepositoryConfigLoader configLoader,
            ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine,
            FindingSuppressionEngine suppression,
            PublicationHandoffService publication,
            UsageAccountingService usageAccounting) {
    }

    private UsageReservationResult reservation(UsageReservationResult.Outcome outcome,
            boolean allowed, int limit, long used) {
        UsagePeriod period = UsagePeriod.utcMonthContaining(Instant.parse("2026-09-09T12:00:00Z"));
        long remaining = Math.max(0, limit - used);
        return new UsageReservationResult(outcome, new QuotaDecision(allowed, limit, used, remaining,
                period, allowed ? QuotaDecision.Reason.AVAILABLE : QuotaDecision.Reason.EXHAUSTED));
    }
}
