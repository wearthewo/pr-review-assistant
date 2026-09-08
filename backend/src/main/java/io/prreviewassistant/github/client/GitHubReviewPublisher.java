package io.prreviewassistant.github.client;

import io.prreviewassistant.review.publication.PublicationPayload;

/** Narrow installation-authenticated boundary for durable pull-request review publication. */
public interface GitHubReviewPublisher {

    GitHubPublishedReview create(
            long installationId,
            String owner,
            String repository,
            int pullRequestNumber,
            String commitId,
            PublicationPayload payload,
            int maxResponseBytes);

    GitHubReviewPage list(
            long installationId,
            String owner,
            String repository,
            int pullRequestNumber,
            int page,
            int maxResponseBytes);
}
