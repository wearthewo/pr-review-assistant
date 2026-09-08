package io.prreviewassistant.review.analysis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FindingSuppressionProperties.class)
class FindingSuppressionConfiguration {

    @Bean
    FindingSuppressionEngine findingSuppressionEngine(FindingSuppressionProperties properties) {
        return new DeterministicFindingSuppressionEngine(properties);
    }
}
