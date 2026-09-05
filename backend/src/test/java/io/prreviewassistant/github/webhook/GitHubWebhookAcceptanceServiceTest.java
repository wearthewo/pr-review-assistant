package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class GitHubWebhookAcceptanceServiceTest {

    @Test
    void duplicateDeliveryDoesNotRunEventProcessing() {
        GitHubWebhookStore store = mock(GitHubWebhookStore.class);
        GitHubWebhookEventProcessor processor = mock(GitHubWebhookEventProcessor.class);
        GitHubWebhookDelivery delivery = delivery();
        JsonNode payload = JsonMapper.builder().build().createObjectNode();
        when(store.store(delivery)).thenReturn(GitHubWebhookStore.StoreResult.DUPLICATE);

        assertThat(new GitHubWebhookAcceptanceService(store, processor).accept(delivery, payload))
                .isEqualTo(GitHubWebhookStore.StoreResult.DUPLICATE);
        verify(processor, never()).process(delivery.eventName(), payload);
    }

    @Test
    void webhookStorageFailureCannotCreateAJob() {
        GitHubWebhookStore store = mock(GitHubWebhookStore.class);
        GitHubWebhookEventProcessor processor = mock(GitHubWebhookEventProcessor.class);
        GitHubWebhookDelivery delivery = delivery();
        JsonNode payload = JsonMapper.builder().build().createObjectNode();
        when(store.store(delivery)).thenThrow(new IllegalStateException("synthetic storage failure"));

        assertThatThrownBy(() -> new GitHubWebhookAcceptanceService(store, processor).accept(delivery, payload))
                .isInstanceOf(IllegalStateException.class);
        verify(processor, never()).process(delivery.eventName(), payload);
    }

    @Test
    void newlyStoredDeliveryIsProcessedBeforeReturning() {
        GitHubWebhookStore store = mock(GitHubWebhookStore.class);
        GitHubWebhookEventProcessor processor = mock(GitHubWebhookEventProcessor.class);
        GitHubWebhookDelivery delivery = delivery();
        JsonNode payload = JsonMapper.builder().build().createObjectNode();
        when(store.store(delivery)).thenReturn(GitHubWebhookStore.StoreResult.ACCEPTED);

        assertThat(new GitHubWebhookAcceptanceService(store, processor).accept(delivery, payload))
                .isEqualTo(GitHubWebhookStore.StoreResult.ACCEPTED);
        verify(processor).process(delivery.eventName(), payload);
    }

    private GitHubWebhookDelivery delivery() {
        return new GitHubWebhookDelivery(
                "delivery", "pull_request", Instant.parse("2026-09-05T12:00:00Z"), "{}");
    }
}
