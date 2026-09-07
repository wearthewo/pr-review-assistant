package io.prreviewassistant.review.context;

import java.util.List;
import java.util.Objects;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;

public record ReviewContext(ReviewTarget target, PullRequestSnapshot pullRequest,
        List<ContextFile> files, List<ContextOmission> omissions, ContextBudgetUsage budgetUsage) {
    public ReviewContext {
        Objects.requireNonNull(target, "target is required");
        Objects.requireNonNull(pullRequest, "pullRequest is required");
        Objects.requireNonNull(budgetUsage, "budgetUsage is required");
        if (target.installationId() != pullRequest.installationId()
                || target.repositoryId() != pullRequest.repositoryId()
                || target.pullRequestNumber() != pullRequest.pullRequestNumber()
                || !target.headSha().equals(pullRequest.headSha())) {
            throw new IllegalArgumentException("review context target does not match its exact snapshot revision");
        }
        files = List.copyOf(files);
        omissions = List.copyOf(omissions);
    }

    public ReviewContext(ReviewTarget target, PullRequestSnapshot pullRequest,
            List<ContextFile> files, ContextBudgetUsage budgetUsage) {
        this(target, pullRequest, files, List.of(), budgetUsage);
    }

    @Override public String toString() {
        return "ReviewContext[target=" + target + ", contextFileCount=" + files.size()
                + ", omissionCount=" + omissions.size() + ", budgetUsage=" + budgetUsage + "]";
    }
}
