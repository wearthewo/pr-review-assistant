package io.prreviewassistant.review.job;

@FunctionalInterface
public interface ReviewJobHandler {

    ReviewJobExecutionResult handle(ClaimedReviewJob job);
}
