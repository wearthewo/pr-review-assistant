package io.prreviewassistant.github.webhook;

public interface GitHubWebhookStore {

    StoreResult store(GitHubWebhookDelivery delivery);

    enum StoreResult {
        ACCEPTED,
        DUPLICATE
    }
}
