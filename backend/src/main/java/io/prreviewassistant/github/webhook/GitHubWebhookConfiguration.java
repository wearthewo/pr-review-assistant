package io.prreviewassistant.github.webhook;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubWebhookProperties.class)
class GitHubWebhookConfiguration {
}
