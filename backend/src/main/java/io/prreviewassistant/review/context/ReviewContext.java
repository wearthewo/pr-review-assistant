package io.prreviewassistant.review.context;

import java.util.List;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;

public record ReviewContext(ReviewTarget target, PullRequestSnapshot pullRequest,
        List<ContextFile> files, ContextBudgetUsage budgetUsage) {
    public ReviewContext { files = List.copyOf(files); }
    @Override public String toString() { return "ReviewContext[target=" + target + ", contextFileCount=" + files.size() + ", budgetUsage=" + budgetUsage + "]"; }
}
