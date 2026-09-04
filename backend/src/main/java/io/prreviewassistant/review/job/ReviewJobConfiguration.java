package io.prreviewassistant.review.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({ReviewJobProperties.class, ReviewWorkerProperties.class})
class ReviewJobConfiguration {

    @Bean
    ReviewJobRetryPolicy reviewJobRetryPolicy(ReviewJobProperties properties) {
        return new ReviewJobRetryPolicy(properties.retryBaseDelay(), properties.retryMaxDelay());
    }
}
