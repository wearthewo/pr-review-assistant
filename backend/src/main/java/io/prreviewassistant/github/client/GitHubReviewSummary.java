package io.prreviewassistant.github.client;

import java.time.Instant;

public record GitHubReviewSummary(long id, String body, Instant publishedAt) {
    @Override public String toString(){return "GitHubReviewSummary[id="+id+", body=<redacted>]";}
}
