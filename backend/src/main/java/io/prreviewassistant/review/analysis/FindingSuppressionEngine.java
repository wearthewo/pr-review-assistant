package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.context.ReviewContext;

public interface FindingSuppressionEngine {
    ValidatedReview validate(ReviewAnalysis analysis, ReviewContext context);
}
