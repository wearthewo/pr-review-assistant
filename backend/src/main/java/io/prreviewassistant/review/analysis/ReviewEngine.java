package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.context.ReviewContext;

public interface ReviewEngine {
    ReviewAnalysis analyze(ReviewContext context);
}
