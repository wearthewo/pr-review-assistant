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

@Component
final class PullRequestRetrievalJobHandler implements ReviewJobHandler {

    private final PullRequestLoader loader;
    private final ReviewContextBuilder contextBuilder;
    private final ReviewEngine reviewEngine;
    private final FindingSuppressionEngine suppressionEngine;

    @Autowired
    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ObjectProvider<ReviewEngine> reviewEngineProvider,
            FindingSuppressionEngine suppressionEngine) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngineProvider.getIfAvailable();
        this.suppressionEngine = suppressionEngine;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder,
            ReviewEngine reviewEngine, FindingSuppressionEngine suppressionEngine) {
        this.loader = loader;
        this.contextBuilder = contextBuilder;
        this.reviewEngine = reviewEngine;
        this.suppressionEngine = suppressionEngine;
    }

    PullRequestRetrievalJobHandler(PullRequestLoader loader, ReviewContextBuilder contextBuilder) {
        this(loader, contextBuilder, (ReviewEngine) null, null);
    }

    @Override
    public ReviewJobExecutionResult handle(ClaimedReviewJob job) {
        if (job.reviewTarget() == null) {
            return ReviewJobExecutionResult.success();
        }
        try {
            PullRequestLoadResult result = loader.load(job.reviewTarget());
            return switch (result.outcome()) {
                case READY -> handleContext(contextBuilder.build(result.snapshot()));
                case STALE -> ReviewJobExecutionResult.terminal("STALE_PULL_REQUEST_REVISION");
                case TOO_LARGE -> ReviewJobExecutionResult.terminal("PULL_REQUEST_TOO_LARGE");
            };
        } catch (GitHubException exception) {
            return map(exception.type());
        }
    }

    private ReviewJobExecutionResult handleContext(ReviewContextBuildResult result) {
        return switch (result.outcome()) {
            case READY, PARTIAL -> analyze(result);
            case UNAVAILABLE -> ReviewJobExecutionResult.terminal("REVIEW_CONTEXT_UNAVAILABLE");
        };
    }

    private ReviewJobExecutionResult analyze(ReviewContextBuildResult result) {
        if (reviewEngine == null) {
            return ReviewJobExecutionResult.terminal("REVIEW_AI_ANALYSIS_NOT_IMPLEMENTED");
        }
        try {
            ReviewAnalysis analysis = reviewEngine.analyze(result.context());
            suppressionEngine.validate(analysis, result.context());
            return ReviewJobExecutionResult.terminal("REVIEW_PUBLISHING_NOT_IMPLEMENTED");
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
