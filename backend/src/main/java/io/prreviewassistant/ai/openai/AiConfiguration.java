package io.prreviewassistant.ai.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import io.prreviewassistant.ai.AiProvider;
import io.prreviewassistant.ai.ReviewAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReviewAiProperties.class, OpenAiProperties.class})
class AiConfiguration {
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
                          ReviewAiProperties aiProperties, ObjectMapper objectMapper, Clock clock) {
        if (aiProperties.provider() != ReviewAiProperties.Provider.OPENAI) {
            throw new IllegalStateException("Configured AI provider is unsupported");
        }
        return new OpenAiProvider(gateway, openAiProperties, aiProperties, objectMapper, clock);
    }
}
