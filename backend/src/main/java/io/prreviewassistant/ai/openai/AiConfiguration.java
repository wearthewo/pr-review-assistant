package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.prreviewassistant.ai.AiProvider;
import io.prreviewassistant.ai.AiGenerationProfile;
import io.prreviewassistant.ai.ReviewAiProperties;
import io.prreviewassistant.ai.ObservedAiProvider;
import io.prreviewassistant.observability.ApplicationMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReviewAiProperties.class, OpenAiProperties.class})
class AiConfiguration {
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper structuredAiObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.ai", name = "enabled", havingValue = "true")
    OpenAIClient openAIClient(OpenAiProperties properties) {
        return OpenAIOkHttpClient.builder()
                .apiKey(properties.requiredApiKey())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.ai", name = "enabled", havingValue = "true")
    OpenAiGateway openAiGateway(OpenAIClient client) { return new SdkOpenAiGateway(client); }

    @Bean
    @ConditionalOnProperty(prefix = "review.ai", name = "enabled", havingValue = "true")
    AiProvider aiProvider(OpenAiGateway gateway, OpenAiProperties openAiProperties,
                          ReviewAiProperties aiProperties, ObjectMapper objectMapper, Clock clock,
                          ObjectProvider<ApplicationMetrics> metricsProvider) {
        if (aiProperties.provider() != ReviewAiProperties.Provider.OPENAI) {
            throw new IllegalStateException("Configured AI provider is unsupported");
        }
        return new ObservedAiProvider(
                new OpenAiProvider(gateway, openAiProperties, aiProperties, objectMapper, clock),
                metricsProvider.getIfAvailable(ApplicationMetrics::noop), clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.ai", name = "enabled", havingValue = "true")
    AiGenerationProfile aiGenerationProfile(OpenAiProperties properties) {
        return properties.defaultProfile();
    }
}
