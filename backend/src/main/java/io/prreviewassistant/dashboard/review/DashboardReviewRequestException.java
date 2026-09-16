package io.prreviewassistant.dashboard.review;

final class DashboardReviewRequestException extends RuntimeException {
    DashboardReviewRequestException() {
        super("Review history request is invalid");
    }
}
