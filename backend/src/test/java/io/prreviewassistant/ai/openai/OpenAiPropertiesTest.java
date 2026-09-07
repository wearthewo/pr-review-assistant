package io.prreviewassistant.ai.openai;

import io.prreviewassistant.ai.AiReasoningEffort;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class OpenAiPropertiesTest {
    @Test void defaultsCanRepresentBoundedEconomicalConfiguration() {
        var properties = properties("secret", Duration.ofSeconds(60), 1, 2048);
        assertThat(properties.defaultProfile().reasoningEffort()).isEqualTo(AiReasoningEffort.LOW);
        assertThat(properties.maximumAttempts()).isEqualTo(2);
        assertThat(properties.toString()).doesNotContain("secret");
    }

    @Test void missingKeyFailsOnlyWhenExplicitlyRequiredAndDoesNotLeak() {
        var properties = properties("", Duration.ofSeconds(60), 1, 2048);
        assertThatThrownBy(properties::requiredApiKey)
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("sk-");
    }

    @Test void timeoutRetryAndOutputHardCeilingsAreEnforced() {
        assertThatIllegalArgumentException().isThrownBy(() -> properties("key", Duration.ofMinutes(3), 1, 2048));
        assertThatIllegalArgumentException().isThrownBy(() -> properties("key", Duration.ofSeconds(1), 2, 2048));
        assertThatIllegalArgumentException().isThrownBy(() -> properties("key", Duration.ofSeconds(1), 0, 8193));
    }

    private static OpenAiProperties properties(String key, Duration timeout, int retries, int output) {
        return new OpenAiProperties(key, "gpt-5.6-terra", AiReasoningEffort.LOW, output, timeout, retries);
    }
}
