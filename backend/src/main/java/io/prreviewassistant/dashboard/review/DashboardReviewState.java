package io.prreviewassistant.dashboard.review;

public enum DashboardReviewState {
    QUEUED,
    ANALYZING,
    ANALYSIS_FAILED,
    COMPLETED_WITHOUT_PUBLICATION,
    PUBLICATION_PENDING,
    PUBLICATION_UNCERTAIN,
    PUBLISHED,
    PUBLICATION_FAILED
}
