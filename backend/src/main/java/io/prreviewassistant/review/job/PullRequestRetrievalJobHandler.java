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
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointError;
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointException;
import io.prreviewassistant.review.analysis.ReviewAnalysisCheckpointService;
import io.prreviewassistant.review.analysis.ReviewCandidateAnalysis;
import io.prreviewassistant.review.analysis.ReviewEngine;
import io.prreviewassistant.ai.AiProviderErrorType;
import io.prreviewassistant.ai.AiProviderException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import io.prreviewassistant.review.publication.PublicationHandoffService;
import io.prreviewassistant.review.config.EffectiveRepositoryReviewConfig;
import io.prreviewassistant.review.config.RepositoryConfigLoader;
import io.prreviewassistant.usage.UsageAccountingError;
import io.prreviewassistant.usage.UsageAccountingException;
import io.prreviewassistant.usage.UsageAccountingService;
import io.prreviewassistant.usage.UsageReservationResult;

@Component
final class PullRequestRetrievalJobHandler implements ReviewJobHandler {

    private final PullRequestLoader loader;
    private final ReviewContextBuilder contextBuilder;
    private final ReviewEngine reviewEngine;
    private final FindingSuppressionEngine suppressionEngine;
    private final PublicationHandoffService publicationHandoff;
    private final RepositoryConfigLoader repositoryConfigLoader;
    private final UsageAccountingService usageAccounting;
    private final ReviewAnalysisCheckpointService checkpointService;

    @Autowired
    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ObjectProvider<ReviewEngine> reviewEngineProvider,
            FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff,
            RepositoryConfigLoader repositoryConfigLoader,
            UsageAccountingService usageAccounting,
            ReviewAnalysisCheckpointService checkpointService) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngineProvider.getIfAvailable();
        this.suppressionEngine = suppressionEngine;
        this.publicationHandoff = publicationHandoff;
        this.repositoryConfigLoader = repositoryConfigLoader;
        this.usageAccounting = usageAccounting;
        this.checkpointService = checkpointService;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, null, null, null, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, publicationHandoff, null, null, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff, RepositoryConfigLoader repositoryConfigLoader) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, publicationHandoff,
                repositoryConfigLoader, null, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff, RepositoryConfigLoader repositoryConfigLoader,
            UsageAccountingService usageAccounting) {
        this(loader, contextBuilder, reviewEngine, suppressionEngine, publicationHandoff,
                repositoryConfigLoader, usageAccounting, null);
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine,
            PublicationHandoffService publicationHandoff, RepositoryConfigLoader repositoryConfigLoader,
            UsageAccountingService usageAccounting, ReviewAnalysisCheckpointService checkpointService) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngine;
        this.suppressionEngine = suppressionEngine;
        this.publicationHandoff = publicationHandoff;
        this.repositoryConfigLoader = repositoryConfigLoader;
        this.usageAccounting = usageAccounting;
        this.checkpointService = checkpointService;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder) {
        this(loader, contextBuilder, (ReviewEngine) null, null, null, null, null, null);
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
        if (checkpointService != null) {
            try {
                var checkpoint = checkpointService.find(job.tenantContext(), job.id());
                if (checkpoint.isPresent()) {
                    return suppressAndHandoff(job, result, checkpoint.orElseThrow());
                }
            } catch (ReviewAnalysisCheckpointException exception) {
                return map(exception);
            }
        }
        if (reviewEngine == null) {
            return ReviewJobExecutionResult.terminal("REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED");
        }
        ReviewJobExecutionResult reservationFailure = reserveUsage(job);
        if (reservationFailure != null) {
            return reservationFailure;
        }
        try {
            ReviewAnalysis analysis = repositoryConfigLoader == null
                    ? reviewEngine.analyze(result.context())
                    : reviewEngine.analyze(result.context(), config.enabledCategories());
            ReviewCandidateAnalysis candidates = analysis.candidates();
            if (checkpointService == null) {
                consumeUsage(job, analysis.metadata());
            } else {
                checkpointService.createAndConsume(
                        job.tenantContext(), job.id(), candidates, analysis.metadata());
            }
            return suppressAndHandoff(job, result, analysis);
        } catch (AiProviderException exception) {
            return map(exception.errorType());
        } catch (ReviewAnalysisException exception) {
            if (checkpointService == null && exception.consumptionMetadata().isPresent()) {
                try {
                    consumeUsage(job, exception.consumptionMetadata().orElseThrow());
                } catch (UsageAccountingException accountingException) {
                    return map(accountingException);
                }
            }
            return ReviewJobExecutionResult.terminal("AI_INVALID_OUTPUT");
        } catch (UsageAccountingException exception) {
            return map(exception);
        } catch (ReviewAnalysisCheckpointException exception) {
            return map(exception);
        } catch (IllegalArgumentException exception) {
            return ReviewJobExecutionResult.terminal("FINDING_SUPPRESSION_INVALID");
        }
    }

    private ReviewJobExecutionResult suppressAndHandoff(
            ClaimedReviewJob job,
            ReviewContextBuildResult result,
            ReviewCandidateAnalysis analysis) {
        var validated = suppressionEngine.validate(analysis, result.context());
        return handoff(job, result, validated);
    }

    private ReviewJobExecutionResult suppressAndHandoff(
            ClaimedReviewJob job,
            ReviewContextBuildResult result,
            ReviewAnalysis analysis) {
        var validated = suppressionEngine.validate(analysis, result.context());
        return handoff(job, result, validated);
    }

    private ReviewJobExecutionResult handoff(
            ClaimedReviewJob job,
            ReviewContextBuildResult result,
            io.prreviewassistant.review.analysis.ValidatedReview validated) {
        if (validated.findings().isEmpty()) {
            return ReviewJobExecutionResult.success();
        }
        if (publicationHandoff == null) {
            return ReviewJobExecutionResult.terminal("REVIEW_PUBLISHING_NOT_IMPLEMENTED");
        }
        publicationHandoff.handoff(
                job.id(), job.tenantContext(), validated, result.context().pullRequest());
        return ReviewJobExecutionResult.success();
    }

    private ReviewJobExecutionResult reserveUsage(ClaimedReviewJob job) {
        if (usageAccounting == null) {
            return null;
        }
        try {
            UsageReservationResult reservation = usageAccounting.reserve(job.tenantContext(), job.id());
            return switch (reservation.outcome()) {
                case ACQUIRED -> null;
                case QUOTA_EXCEEDED -> ReviewJobExecutionResult.terminal("USAGE_QUOTA_EXCEEDED");
                case EXISTING_RESERVED -> ReviewJobExecutionResult.terminal("USAGE_RESERVATION_AMBIGUOUS");
                case EXISTING_CONSUMED -> ReviewJobExecutionResult.terminal("USAGE_ANALYSIS_RESULT_UNAVAILABLE");
                case EXISTING_RELEASED -> ReviewJobExecutionResult.terminal("USAGE_RESERVATION_RELEASED");
            };
        } catch (UsageAccountingException exception) {
            return map(exception);
        }
    }

    private void consumeUsage(ClaimedReviewJob job,
            io.prreviewassistant.review.analysis.ReviewAnalysisMetadata metadata) {
        if (usageAccounting != null) {
            usageAccounting.consume(job.tenantContext(), job.id(), metadata);
        }
    }

    private ReviewJobExecutionResult map(UsageAccountingException exception) {
        return exception.error() == UsageAccountingError.USAGE_TENANT_MISMATCH
                ? ReviewJobExecutionResult.terminal("USAGE_TENANT_MISMATCH")
                : ReviewJobExecutionResult.retryable("USAGE_ACCOUNTING_FAILED");
    }

    private ReviewJobExecutionResult map(ReviewAnalysisCheckpointException exception) {
        return exception.error() == ReviewAnalysisCheckpointError.INCONSISTENT_STATE
                ? ReviewJobExecutionResult.terminal("USAGE_ANALYSIS_RESULT_UNAVAILABLE")
                : ReviewJobExecutionResult.retryable("ANALYSIS_CHECKPOINT_FAILED");
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
