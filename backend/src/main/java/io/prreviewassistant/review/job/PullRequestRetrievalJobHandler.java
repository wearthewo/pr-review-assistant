package io.prreviewassistant.review.job;

import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import org.springframework.stereotype.Component;

@Component
final class PullRequestRetrievalJobHandler implements ReviewJobHandler {

    private final PullRequestLoader loader;

    PullRequestRetrievalJobHandler(PullRequestLoader loader) {
        this.loader = loader;
    }

    @Override
    public ReviewJobExecutionResult handle(ClaimedReviewJob job) {
        if (job.reviewTarget() == null) {
            return ReviewJobExecutionResult.success();
        }
        try {
            PullRequestLoadResult result = loader.load(job.reviewTarget());
            return switch (result.outcome()) {
                case READY -> ReviewJobExecutionResult.terminal("REVIEW_ANALYSIS_NOT_IMPLEMENTED");
                case STALE -> ReviewJobExecutionResult.terminal("STALE_PULL_REQUEST_REVISION");
                case TOO_LARGE -> ReviewJobExecutionResult.terminal("PULL_REQUEST_TOO_LARGE");
            };
        } catch (GitHubException exception) {
            return map(exception.type());
        }
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
