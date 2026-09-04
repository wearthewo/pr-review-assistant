package io.prreviewassistant.review.job;

import org.springframework.stereotype.Component;

@Component
final class NoOpReviewJobHandler implements ReviewJobHandler {

    @Override
    public ReviewJobExecutionResult handle(ClaimedReviewJob job) {
        return ReviewJobExecutionResult.success();
    }
}
