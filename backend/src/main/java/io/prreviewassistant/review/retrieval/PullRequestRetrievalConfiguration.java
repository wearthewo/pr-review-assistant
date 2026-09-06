package io.prreviewassistant.review.retrieval;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PullRequestFetchProperties.class)
class PullRequestRetrievalConfiguration {
}
