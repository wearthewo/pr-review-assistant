package io.prreviewassistant.review.context;
import org.springframework.boot.context.properties.EnableConfigurationProperties;import org.springframework.context.annotation.Configuration;
@Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ReviewContextProperties.class)
class ReviewContextConfiguration {}
