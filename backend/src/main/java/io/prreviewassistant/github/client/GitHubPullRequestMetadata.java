package io.prreviewassistant.github.client;

public record GitHubPullRequestMetadata(
        int number,
        long repositoryId,
        String headSha,
        String baseSha,
        boolean draft) {
}
