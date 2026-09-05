package io.prreviewassistant.github.webhook;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GitHubWebhookAcceptanceService {

    private final GitHubWebhookStore store;
    private final GitHubWebhookEventProcessor eventProcessor;

    public GitHubWebhookAcceptanceService(
            GitHubWebhookStore store,
            GitHubWebhookEventProcessor eventProcessor) {
        this.store = store;
        this.eventProcessor = eventProcessor;
    }

    @Transactional
    public GitHubWebhookStore.StoreResult accept(GitHubWebhookDelivery delivery, JsonNode payload) {
        GitHubWebhookStore.StoreResult result = store.store(delivery);
        if (result == GitHubWebhookStore.StoreResult.ACCEPTED) {
            eventProcessor.process(delivery.eventName(), payload);
        }
        return result;
    }
}
