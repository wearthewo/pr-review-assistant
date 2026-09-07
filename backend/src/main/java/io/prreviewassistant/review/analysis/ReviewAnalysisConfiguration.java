package io.prreviewassistant.review.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.prreviewassistant.ai.AiGenerationProfile;
import io.prreviewassistant.ai.AiProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReviewAnalysisProperties.class)
class ReviewAnalysisConfiguration {

    @Bean
    ReviewContextSerializer reviewContextSerializer(ObjectMapper objectMapper) {
        return new ReviewContextSerializer(objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "review.ai", name = "enabled", havingValue = "true")
    ReviewEngine reviewEngine(AiProvider provider, ObjectMapper objectMapper,
            ReviewContextSerializer serializer, ReviewAnalysisProperties properties,
            AiGenerationProfile generationProfile) {
        return new AiReviewEngine(provider, objectMapper, serializer, properties, generationProfile);
    }
}
