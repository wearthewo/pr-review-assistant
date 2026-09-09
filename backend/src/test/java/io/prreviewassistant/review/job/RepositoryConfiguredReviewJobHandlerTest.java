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
import static org.mockito.Mockito.when;

class RepositoryConfiguredReviewJobHandlerTest {
    private static final ReviewTarget TARGET = new ReviewTarget(1, 2, 3, "a".repeat(40));

    @Test
    void configOnlyPullRequestLoadsConfigThenCompletesWithoutContextAiOrPublication() {
        var fixture = fixture(snapshot(file(".reviewbot.yml")));

        var result = fixture.handler.handle(claim());

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        verify(fixture.configLoader).load(any());
        verify(fixture.contextBuilder, never()).build(any(), any());
        verify(fixture.reviewEngine, never()).analyze(any(), any());
        verify(fixture.publication, never()).handoff(any(), any(), any());
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
        verify(fixture.publication, never()).handoff(any(), any(), any());
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
        verify(fixture.publication, never()).handoff(any(), any(), any());
    }

    @Test
    void ignorePolicyFiltersBeforeContextAndEnabledCategoriesReachAi() {
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
        when(fixture.reviewEngine.analyze(context, categories)).thenReturn(analysis);
        ValidatedReview validated = mock(ValidatedReview.class);
        when(validated.findings()).thenReturn(List.of());
        when(fixture.suppression.validate(analysis, context)).thenReturn(validated);

        assertThat(fixture.handler.handle(claim()).outcome())
                .isEqualTo(ReviewJobExecutionResult.Outcome.SUCCESS);
        assertThat(filtered.getValue().changedFiles()).extracting(ChangedFile::path)
                .containsExactly("src/A.java");
        verify(fixture.reviewEngine).analyze(context, categories);
        verify(fixture.publication, never()).handoff(any(), any(), any());
    }

    @Test
    void transientConfigFetchUsesExistingDurableRetryClassification() {
        var fixture = fixture(snapshot(file("src/A.java")));
        when(fixture.configLoader.load(any())).thenThrow(GitHubException.transientFailure());

        var result = fixture.handler.handle(claim());

        assertThat(result.outcome()).isEqualTo(ReviewJobExecutionResult.Outcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode().value()).isEqualTo("GITHUB_TRANSIENT_FAILURE");
        verify(fixture.reviewEngine, never()).analyze(any(), any());
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
        return new Fixture(new PullRequestRetrievalJobHandler(loader, contextBuilder, reviewEngine,
                suppression, publication, configLoader), loader, configLoader, contextBuilder,
                reviewEngine, suppression, publication);
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
                now, now.plusSeconds(60), TARGET);
    }

    private record Fixture(
            PullRequestRetrievalJobHandler handler,
            PullRequestLoader loader,
            RepositoryConfigLoader configLoader,
            ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine,
            FindingSuppressionEngine suppression,
            PublicationHandoffService publication) {
    }
}
