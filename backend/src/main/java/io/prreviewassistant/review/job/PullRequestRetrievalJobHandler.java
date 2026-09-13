package io.prreviewassistant.review.job;

import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import io.prreviewassistant.review.context.ReviewContextBuildResult;
import io.prreviewassistant.review.context.ReviewContextBuilder;
import io.prreviewassistant.review.analysis.ReviewAnalysisException;
import io.prreviewassistant.review.analysis.FindingSuppressionEngine;
import io.prreviewassistant.review.analysis.ReviewAnalysis;
import io.prreviewassistant.review.analysis.ReviewEngine;
import io.prreviewassistant.ai.AiProviderErrorType;
import io.prreviewassistant.ai.AiProviderException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.prreviewassistant.review.publication.PublicationHandoffService;
import io.prreviewassistant.review.config.EffectiveRepositoryReviewConfig;
import io.prreviewassistant.review.config.RepositoryConfigLoader;

@Component
final class PullRequestRetrievalJobHandler implements ReviewJobHandler {

    private final PullRequestLoader loader;
    private final ReviewContextBuilder contextBuilder;
    private final ReviewEngine reviewEngine;
    private final FindingSuppressionEngine suppressionEngine;
    private final PublicationHandoffService publicationHandoff;
    private final RepositoryConfigLoader repositoryConfigLoader;

    @Autowired
    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ObjectProvider<ReviewEngine> reviewEngineProvider,
            FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff,
            RepositoryConfigLoader repositoryConfigLoader) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngineProvider.getIfAvailable();
        this.suppressionEngine = suppressionEngine;
        this.publicationHandoff = publicationHandoff;
        this.repositoryConfigLoader = repositoryConfigLoader;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, publicationHandoff, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff, RepositoryConfigLoader repositoryConfigLoader) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngine;
        this.suppressionEngine = suppressionEngine;
        this.publicationHandoff = publicationHandoff;
        this.repositoryConfigLoader = repositoryConfigLoader;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder) {
        this(loader, contextBuilder, (ReviewEngine) null, null);
    }

    @Override
    public ReviewJobExecutionResult handle(ClaimedReviewJob job) {
        if (job.reviewTarget() == null) {
            return ReviewJobExecutionResult.success();
        }
        if (job.tenantContext() == null) {
            return ReviewJobExecutionResult.terminal("TENANT_NOT_RESOLVED");
        }
        if (job.tenantContext().githubInstallationId() != job.reviewTarget().installationId()
                || job.tenantContext().githubRepositoryId() != job.reviewTarget().repositoryId()) {
            return ReviewJobExecutionResult.terminal("TENANT_REPOSITORY_OWNERSHIP_MISMATCH");
        }
        if (publicationHandoff != null && publicationHandoff.alreadyHandedOff(job.id())) {
            return ReviewJobExecutionResult.success();
        }
        try {
            PullRequestLoadResult result = loader.load(job.reviewTarget());
            return switch (result.outcome()) {
                case READY -> configureAndBuild(job, result.snapshot());
                case STALE -> ReviewJobExecutionResult.terminal("STALE_PULL_REQUEST_REVISION");
                case TOO_LARGE -> ReviewJobExecutionResult.terminal("PULL_REQUEST_TOO_LARGE");
            };
        } catch (GitHubException exception) {
            return map(exception.type());
        }
    }

    private ReviewJobExecutionResult configureAndBuild(ClaimedReviewJob job,
            io.prreviewassistant.review.retrieval.PullRequestSnapshot snapshot) {
        EffectiveRepositoryReviewConfig config = repositoryConfigLoader == null
                ? EffectiveRepositoryReviewConfig.defaults()
                : repositoryConfigLoader.load(snapshot).effectiveConfig();
        var filteredSnapshot = config.filter(snapshot);
        if (repositoryConfigLoader != null
                && (filteredSnapshot.changedFiles().isEmpty() || config.enabledCategories().isEmpty())) {
            return ReviewJobExecutionResult.success();
        }
        ReviewContextBuildResult context = repositoryConfigLoader == null
                ? contextBuilder.build(filteredSnapshot)
                : contextBuilder.build(filteredSnapshot, config);
        return handleContext(job, context, config);
    }

    private ReviewJobExecutionResult handleContext(ClaimedReviewJob job, ReviewContextBuildResult result,
            EffectiveRepositoryReviewConfig config) {
        return switch (result.outcome()) {
            case READY, PARTIAL -> analyze(job, result, config);
            case UNAVAILABLE -> ReviewJobExecutionResult.terminal("REVIEW_CONTEXT_UNAVAILABLE");
        };
    }

    private ReviewJobExecutionResult analyze(ClaimedReviewJob job, ReviewContextBuildResult result,
            EffectiveRepositoryReviewConfig config) {
        if (reviewEngine == null) {
            return ReviewJobExecutionResult.terminal("REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED");
        }
        try {
            ReviewAnalysis analysis = repositoryConfigLoader == null
                    ? reviewEngine.analyze(result.context())
                    : reviewEngine.analyze(result.context(), config.enabledCategories());
            var validated = suppressionEngine.validate(analysis, result.context());
            if (validated.findings().isEmpty()) {
                return ReviewJobExecutionResult.success();
            }
            if (publicationHandoff == null) {
                return ReviewJobExecutionResult.terminal("REVIEW_PUBLISHING_NOT_IMPLEMENTED");
            }
            publicationHandoff.handoff(
                    job.id(), job.tenantContext(), validated, result.context().pullRequest());
            return ReviewJobExecutionResult.success();
        } catch (AiProviderException exception) {
            return map(exception.errorType());
        } catch (ReviewAnalysisException exception) {
            return ReviewJobExecutionResult.terminal("AI_INVALID_OUTPUT");
        } catch (IllegalArgumentException exception) {
            return ReviewJobExecutionResult.terminal("FINDING_SUPPRESSION_INVALID");
        }
    }

    private ReviewJobExecutionResult map(AiProviderErrorType type) {
        return switch (type) {
            case RATE_LIMITED -> ReviewJobExecutionResult.retryable("AI_RATE_LIMITED");
            case TRANSIENT -> ReviewJobExecutionResult.retryable("AI_TRANSIENT_FAILURE");
            case TIMEOUT -> ReviewJobExecutionResult.retryable("AI_TIMEOUT");
            case AUTHENTICATION -> ReviewJobExecutionResult.terminal("AI_AUTHENTICATION_FAILED");
            case PERMISSION_DENIED -> ReviewJobExecutionResult.terminal("AI_PERMISSION_DENIED");
            case MODEL_UNAVAILABLE -> ReviewJobExecutionResult.terminal("AI_MODEL_UNAVAILABLE");
            case INVALID_REQUEST, INPUT_TOO_LARGE, OUTPUT_INVALID ->
                    ReviewJobExecutionResult.terminal("AI_INVALID_OUTPUT");
            case PROVIDER_FAILURE -> ReviewJobExecutionResult.terminal("AI_ANALYSIS_FAILED");
        };
    }

    private ReviewJobExecutionResult map(GitHubErrorType type) {
        return switch (type) {
            case RATE_LIMITED -> ReviewJobExecutionResult.retryable("GITHUB_RATE_LIMITED");
            case TRANSIENT_FAILURE -> ReviewJobExecutionResult.retryable("GITHUB_TRANSIENT_FAILURE");
            case AUTHENTICATION_REJECTED ->
                    ReviewJobExecutionResult.terminal("GITHUB_AUTHENTICATION_REJECTED");
            case INSTALLATION_NOT_FOUND, RESOURCE_NOT_FOUND ->
                    ReviewJobExecutionResult.terminal("GITHUB_RESOURCE_NOT_ACCESSIBLE");
            case MALFORMED_RESPONSE -> ReviewJobExecutionResult.terminal("GITHUB_RESPONSE_INVALID");
            case RESPONSE_TOO_LARGE -> ReviewJobExecutionResult.terminal("PULL_REQUEST_TOO_LARGE");
            case INVALID_CONFIGURATION, INVALID_INSTALLATION_ID, JWT_GENERATION_FAILED ->
                    ReviewJobExecutionResult.terminal("GITHUB_LOCAL_CONFIGURATION_INVALID");
        };
    }
}
