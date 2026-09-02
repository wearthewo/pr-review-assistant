package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubWebhookPropertiesTest {

    @Test
    void redactsTheSecretFromItsStringRepresentation() {
        String secret = "never-print-this-secret";
        GitHubWebhookProperties properties = new GitHubWebhookProperties(secret, DataSize.ofMegabytes(1));

        assertThat(properties.toString()).doesNotContain(secret).contains("<redacted>");
    }

    @Test
    void rejectsMissingWeakOrUnsafeConfigurationWithoutEchoingIt() {
        String oversizedSecret = "x".repeat(1025);

        assertThatThrownBy(() -> new GitHubWebhookProperties("short", DataSize.ofMegabytes(1)))
                .hasMessage("GitHub webhook configuration is invalid")
                .hasMessageNotContaining("short");
        assertThatThrownBy(() -> new GitHubWebhookProperties(oversizedSecret, DataSize.ofMegabytes(1)))
                .hasMessageNotContaining(oversizedSecret);
        assertThatThrownBy(() -> new GitHubWebhookProperties("valid-secret-value", DataSize.ofBytes(0)))
                .hasMessage("GitHub webhook configuration is invalid");
        assertThatThrownBy(() -> new GitHubWebhookProperties(
                "valid-secret-value", DataSize.ofMegabytes(26)))
                .hasMessage("GitHub webhook configuration is invalid");
    }
}
