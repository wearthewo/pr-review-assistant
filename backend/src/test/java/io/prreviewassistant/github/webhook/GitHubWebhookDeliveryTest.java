package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class GitHubWebhookDeliveryTest {

    @Test
    void doesNotExposePayloadInItsStringRepresentation() {
        String payload = "{\"private\":\"source-content\"}";
        GitHubWebhookDelivery delivery = new GitHubWebhookDelivery(
                "delivery-1", "push", Instant.EPOCH, payload);

        assertThat(delivery.toString()).doesNotContain(payload).doesNotContain("source-content");
    }
}
