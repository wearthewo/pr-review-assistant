package io.prreviewassistant.github.client;

import java.time.Instant;

public record GitHubPublishedReview(long id, Instant publishedAt) {
    public GitHubPublishedReview { if(id<=0||publishedAt==null)throw new IllegalArgumentException("published review is invalid"); }
}
