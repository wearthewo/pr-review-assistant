package io.prreviewassistant.github.webhook;

import java.time.Instant;

public record GitHubWebhookDelivery(
        String deliveryId,
        String eventName,
        Instant receivedAt,
        String rawPayload) {

    @Override
    public String toString() {
        return "GitHubWebhookDelivery[deliveryId=" + deliveryId
                + ", eventName=" + eventName
                + ", receivedAt=" + receivedAt
                + ", rawPayload=<redacted>]";
    }
}
