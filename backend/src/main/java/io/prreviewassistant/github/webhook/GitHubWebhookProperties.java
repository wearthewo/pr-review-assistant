package io.prreviewassistant.github.webhook;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("github.webhook")
@Validated
public final class GitHubWebhookProperties {

    private static final long GITHUB_MAX_PAYLOAD_BYTES = DataSize.ofMegabytes(25).toBytes();

    @NotBlank
    @Size(min = 16, max = 1024)
    private final String secret;

    @NotNull
    private final DataSize maxBodySize;

    public GitHubWebhookProperties(String secret, DataSize maxBodySize) {
        if (secret == null || secret.isBlank()
                || secret.length() < 16 || secret.length() > 1024
                || secret.getBytes(StandardCharsets.UTF_8).length > 1024) {
            throw GitHubWebhookException.invalidConfiguration();
        }
        if (maxBodySize == null || maxBodySize.toBytes() <= 0
                || maxBodySize.toBytes() > GITHUB_MAX_PAYLOAD_BYTES) {
            throw GitHubWebhookException.invalidConfiguration();
        }
        this.secret = secret;
        this.maxBodySize = maxBodySize;
    }

    byte[] secretBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public int maxBodyBytes() {
        return Math.toIntExact(maxBodySize.toBytes());
    }

    @Override
    public String toString() {
        return "GitHubWebhookProperties[secret=<redacted>, maxBodySize=" + maxBodySize + "]";
    }
}
