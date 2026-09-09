package io.prreviewassistant.review.analysis;

import io.prreviewassistant.review.context.ReviewContext;
import java.util.Set;

public interface ReviewEngine {
    ReviewAnalysis analyze(ReviewContext context);

    default ReviewAnalysis analyze(ReviewContext context, Set<ReviewFindingCategory> enabledCategories) {
        return analyze(context);
    }
}
