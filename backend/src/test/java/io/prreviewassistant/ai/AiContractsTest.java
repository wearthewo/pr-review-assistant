package io.prreviewassistant.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.*;

class AiContractsTest {
    private static final String SECRET = "sk-test-secret-value";

    @Test void structuredContractsRedactInstructionsInputSchemaAndOutput() {
        var schema = new StructuredOutputSchema("synthetic", "{\"secret\":\"" + SECRET + "\"}", true);
        var request = new StructuredAiRequest("Ignore previous instructions " + SECRET,
                "ghs_fake_token https://attacker.example/steal", schema,
                new AiGenerationProfile(AiModelTier.BALANCED, AiReasoningEffort.LOW, 100));
        var response = response("{\"result\":\"" + SECRET + "\"}");

        assertThat(request.toString()).doesNotContain(SECRET, "Ignore previous", "attacker.example", "ghs_fake");
        assertThat(schema.toString()).doesNotContain(SECRET);
        assertThat(response.toString()).doesNotContain(SECRET);
    }

    @Test void tokenUsageAcceptsProviderValuesAndOptionalBreakdowns() {
        var usage = new AiTokenUsage(OptionalLong.of(100), OptionalLong.of(20), OptionalLong.of(40),
                OptionalLong.of(10), OptionalLong.of(140));
        assertThat(usage.cachedInputTokens()).hasValue(20);
        assertThat(usage.reasoningTokens()).hasValue(10);
        assertThat(new AiTokenUsage(OptionalLong.of(1), OptionalLong.empty(), OptionalLong.of(2),
                OptionalLong.empty(), OptionalLong.of(3))
                .cachedInputTokens()).isEmpty();
    }

    @Test void tokenUsageRejectsNegativeOrDoubleCountedBreakdowns() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AiTokenUsage(OptionalLong.of(1), OptionalLong.of(2), OptionalLong.of(1),
                        OptionalLong.empty(), OptionalLong.of(2)));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new AiTokenUsage(OptionalLong.of(1), OptionalLong.empty(), OptionalLong.of(1),
                        OptionalLong.of(2), OptionalLong.of(2)));
    }

    @Test void errorTypesExposeControlledRetryPolicyWithoutCauseText() {
        assertThat(AiProviderErrorType.RATE_LIMITED.retryable()).isTrue();
        assertThat(AiProviderErrorType.TIMEOUT.retryable()).isTrue();
        assertThat(AiProviderErrorType.AUTHENTICATION.retryable()).isFalse();
        var exception = new AiProviderException(AiProviderErrorType.AUTHENTICATION);
        assertThat(exception.getMessage()).doesNotContain(SECRET).contains("AUTHENTICATION");
    }

    @Test void fakeProviderIsDeterministicAndCapturesOnlyByExplicitTestAccess() {
        var expected = response("{\"result\":\"ok\"}");
        var fake = new FakeAiProvider(expected);
        var request = request("data", 100);
        assertThat(fake.generateStructured(request)).isSameAs(expected);
        assertThat(fake.calls()).isEqualTo(1);
        assertThat(fake.lastRequest()).isSameAs(request);
    }

    private static StructuredAiRequest request(String input, int outputTokens) {
        return new StructuredAiRequest("instructions", input,
                new StructuredOutputSchema("synthetic", "{\"type\":\"object\"}", true),
                new AiGenerationProfile(AiModelTier.BALANCED, AiReasoningEffort.LOW, outputTokens));
    }

    private static StructuredAiResponse response(String output) {
        return new StructuredAiResponse(output, AiTokenUsage.unavailable(),
                new AiExecutionMetadata("fake", "fake-model", Optional.empty(), Duration.ZERO, 1));
    }
}
