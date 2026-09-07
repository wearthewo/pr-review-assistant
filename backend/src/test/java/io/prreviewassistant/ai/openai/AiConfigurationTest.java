package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.AiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "review.ai.provider=openai", "review.ai.max-input-chars=1000", "review.ai.max-schema-size=1KB",
                    "review.ai.openai.model=gpt-5.6-terra", "review.ai.openai.reasoning-effort=low",
                    "review.ai.openai.max-output-tokens=100", "review.ai.openai.timeout=10s",
                    "review.ai.openai.max-retries=0");

    @Test void disabledStartsWithoutApiKeyAndCreatesNoProviderClient() {
        runner.withPropertyValues("review.ai.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(AiProvider.class);
        });
    }

    @Test void enabledWithoutApiKeyFailsClearlyWithoutSecretMaterial() {
        runner.withPropertyValues("review.ai.enabled=true", "review.ai.openai.api-key=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessageNotContaining("Authorization").hasMessageNotContaining("sk-");
        });
    }

    @Test void enabledWithApiKeyBuildsOneReusableProviderBeanWithoutNetworkCall() {
        runner.withPropertyValues("review.ai.enabled=true", "review.ai.openai.api-key=test-only-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiProvider.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
    }
}
