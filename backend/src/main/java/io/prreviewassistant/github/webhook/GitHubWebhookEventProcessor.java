package io.prreviewassistant.github.webhook;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

@Component
public final class GitHubWebhookEventProcessor {

    private static final String PULL_REQUEST_EVENT = "pull_request";

    private final PullRequestWebhookProcessor pullRequestProcessor;

    public GitHubWebhookEventProcessor(PullRequestWebhookProcessor pullRequestProcessor) {
        this.pullRequestProcessor = pullRequestProcessor;
    }

    public GitHubWebhookProcessingResult process(String eventName, JsonNode payload) {
        if (!PULL_REQUEST_EVENT.equals(eventName)) {
            return GitHubWebhookProcessingResult.IGNORED;
        }
        return pullRequestProcessor.process(payload);
    }
}
