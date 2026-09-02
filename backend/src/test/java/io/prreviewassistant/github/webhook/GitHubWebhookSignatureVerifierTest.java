package io.prreviewassistant.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class GitHubWebhookSignatureVerifierTest {

    private static final String SECRET = "It's a Secret to Everybody";
    private static final String PUBLISHED_SIGNATURE =
            "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
    private final GitHubWebhookSignatureVerifier verifier = new GitHubWebhookSignatureVerifier(
            new GitHubWebhookProperties(SECRET, DataSize.ofMegabytes(1)));

    @Test
    void acceptsGitHubsPublishedHmacSha256TestVector() {
        assertThat(verifier.isValid("Hello, World!".getBytes(StandardCharsets.UTF_8), PUBLISHED_SIGNATURE))
                .isTrue();
    }

    @Test
    void rejectsAnAlteredBody() {
        assertThat(verifier.isValid("Hello, World?".getBytes(StandardCharsets.UTF_8), PUBLISHED_SIGNATURE))
                .isFalse();
    }

    @Test
    void rejectsMalformedMissingAndSha1Signatures() {
        assertThat(verifier.isValid(new byte[0], null)).isFalse();
        assertThat(verifier.isValid(new byte[0], "sha256=not-hex")).isFalse();
        assertThat(verifier.isValid(new byte[0], "sha256=" + "0".repeat(63))).isFalse();
        assertThat(verifier.isValid(new byte[0], "sha1=" + "0".repeat(40))).isFalse();
    }

    @Test
    void rejectsTheRightSignatureWhenConfiguredWithTheWrongSecret() {
        GitHubWebhookSignatureVerifier wrongSecretVerifier = new GitHubWebhookSignatureVerifier(
                new GitHubWebhookProperties("a-different-secret", DataSize.ofMegabytes(1)));

        assertThat(wrongSecretVerifier.isValid(
                "Hello, World!".getBytes(StandardCharsets.UTF_8), PUBLISHED_SIGNATURE)).isFalse();
    }

    @Test
    void comparesExactJsonBytesRatherThanJsonMeaning() {
        byte[] signed = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        byte[] reformatted = "{ \"action\" : \"opened\" }".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookTestSupport.sign(SECRET, signed);

        assertThat(verifier.isValid(signed, signature)).isTrue();
        assertThat(verifier.isValid(reformatted, signature)).isFalse();
    }

    @Test
    void handlesUnicodeAsRawUtf8Bytes() {
        byte[] body = "{\"message\":\"Καλημέρα 🌍\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifier.isValid(body, WebhookTestSupport.sign(SECRET, body))).isTrue();
    }
}
