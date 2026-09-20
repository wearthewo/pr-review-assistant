package io.prreviewassistant.review.analysis;

import io.prreviewassistant.observability.ApplicationMetrics;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FindingSuppressionProperties.class)
class FindingSuppressionConfiguration {

    @Bean
    FindingSuppressionEngine findingSuppressionEngine(
            FindingSuppressionProperties properties,
            ObjectProvider<ApplicationMetrics> metricsProvider) {
        return new DeterministicFindingSuppressionEngine(
                properties, metricsProvider.getIfAvailable(ApplicationMetrics::noop));
    }
}
