package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.AiExecutionMetadata;
import io.prreviewassistant.ai.AiGenerationProfile;
import io.prreviewassistant.ai.AiModelTier;
import io.prreviewassistant.ai.AiProvider;
import io.prreviewassistant.ai.AiReasoningEffort;
import io.prreviewassistant.ai.AiTokenUsage;
import io.prreviewassistant.ai.StructuredAiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewAnalysisConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReviewAnalysisConfiguration.class, TestDependencies.class)
            .withPropertyValues(
                    "review.analysis.max-findings=5",
                    "review.analysis.minimum-candidate-confidence=70");

    @Test void disabledAiCreatesNoReviewEngine() {
        runner.withPropertyValues("review.ai.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ReviewEngine.class);
        });
    }

    @Test void enabledAiWiresProviderNeutralReviewEngine() {
        runner.withPropertyValues("review.ai.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReviewEngine.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestDependencies {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AiGenerationProfile generationProfile() {
            return new AiGenerationProfile(AiModelTier.BALANCED, AiReasoningEffort.LOW, 2048);
        }
        @Bean AiProvider aiProvider() {
            return request -> new StructuredAiResponse("{\"findings\":[]}", AiTokenUsage.unavailable(),
                    new AiExecutionMetadata("fake", "fake-model", Optional.empty(), Duration.ZERO, 1));
        }
    }
}
