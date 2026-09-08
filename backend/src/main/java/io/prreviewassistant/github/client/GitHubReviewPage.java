package io.prreviewassistant.github.client;

import java.util.List;

public record GitHubReviewPage(List<GitHubReviewSummary> reviews, boolean hasNextPage) {
    public GitHubReviewPage { reviews=List.copyOf(reviews); }
}
