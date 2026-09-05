package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubWebhookServiceTest {

    private static final String SECRET = "test-webhook-secret-with-entropy";
    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private final AtomicReference<GitHubWebhookDelivery> stored = new AtomicReference<>();
    private final GitHubWebhookService service = new GitHubWebhookService(
            new GitHubWebhookSignatureVerifier(
                    new GitHubWebhookProperties(SECRET, DataSize.ofMegabytes(1))),
            JsonMapper.builder().build(),
            new GitHubWebhookAcceptanceService(
                    delivery -> {
                        stored.set(delivery);
                        return GitHubWebhookStore.StoreResult.ACCEPTED;
                    },
                    mock(GitHubWebhookEventProcessor.class)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validatesThenStoresTheOriginalJsonAndControlledTimestamp() {
        byte[] body = "{ \"z\": 1, \"a\": \"value\" }\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(service.ingest(body, WebhookTestSupport.sign(SECRET, body), "opaque-delivery", "ping"))
                .isEqualTo(GitHubWebhookStore.StoreResult.ACCEPTED);
        assertThat(stored.get()).isEqualTo(new GitHubWebhookDelivery(
                "opaque-delivery", "ping", NOW, new String(body, StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsInvalidJsonAndDoesNotPersistIt() {
        byte[] body = "{not-json}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.ingest(
                body, WebhookTestSupport.sign(SECRET, body), "delivery", "ping"))
                .isInstanceOf(GitHubWebhookException.class)
                .hasMessage("GitHub webhook request is invalid")
                .hasMessageNotContaining("not-json");
        assertThat(stored).hasValue(null);
    }

    @Test
    void rejectsTrailingJsonValuesAndInvalidUtf8() {
        byte[] trailing = "{} {}".getBytes(StandardCharsets.UTF_8);
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};

        assertThatThrownBy(() -> service.ingest(
                trailing, WebhookTestSupport.sign(SECRET, trailing), "delivery", "ping"))
                .isInstanceOf(GitHubWebhookException.class);
        assertThatThrownBy(() -> service.ingest(
                invalidUtf8, WebhookTestSupport.sign(SECRET, invalidUtf8), "delivery", "ping"))
                .isInstanceOf(GitHubWebhookException.class);
    }

    @Test
    void rejectsAnEmptyBody() {
        byte[] body = new byte[0];
        byte[] whitespace = "  \r\n".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.ingest(
                body, WebhookTestSupport.sign(SECRET, body), "delivery", "ping"))
                .isInstanceOf(GitHubWebhookException.class)
                .hasMessage("GitHub webhook request is invalid");
        assertThatThrownBy(() -> service.ingest(
                whitespace, WebhookTestSupport.sign(SECRET, whitespace), "delivery", "ping"))
                .isInstanceOf(GitHubWebhookException.class)
                .hasMessage("GitHub webhook request is invalid");
        assertThat(stored).hasValue(null);
    }

    @Test
    void verifiesSignatureBeforeMetadataOrJsonValidation() {
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.ingest(body, "sha256=" + "0".repeat(64), null, null))
                .isInstanceOf(GitHubWebhookException.class)
                .hasMessage("GitHub webhook authentication failed")
                .hasMessageNotContaining("not-json");
    }

    @Test
    void rejectsMissingBlankLongOrControlCharacterMetadata() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookTestSupport.sign(SECRET, body);

        assertThatThrownBy(() -> service.ingest(body, signature, null, "ping"))
                .isInstanceOf(GitHubWebhookException.class);
        assertThatThrownBy(() -> service.ingest(body, signature, "delivery", " "))
                .isInstanceOf(GitHubWebhookException.class);
        assertThatThrownBy(() -> service.ingest(body, signature, "d".repeat(129), "ping"))
                .isInstanceOf(GitHubWebhookException.class);
        assertThatThrownBy(() -> service.ingest(body, signature, "delivery", "e".repeat(65)))
                .isInstanceOf(GitHubWebhookException.class);
        assertThatThrownBy(() -> service.ingest(body, signature, "delivery\nforged", "ping"))
                .isInstanceOf(GitHubWebhookException.class);
    }
}
