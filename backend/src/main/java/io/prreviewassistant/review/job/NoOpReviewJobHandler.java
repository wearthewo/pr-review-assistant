package io.prreviewassistant.review.job;

import org.springframework.stereotype.Component;

@Component
final class NoOpReviewJobHandler implements ReviewJobHandler {

    private static final String REVIEW_NOT_IMPLEMENTED = "REVIEW_HANDLER_NOT_IMPLEMENTED";

    @Override
    public ReviewJobExecutionResult handle(ClaimedReviewJob job) {
        if (job.reviewTarget() != null) {
            return ReviewJobExecutionResult.terminal(REVIEW_NOT_IMPLEMENTED);
        }
        return ReviewJobExecutionResult.success();
    }
}
