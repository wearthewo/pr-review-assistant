package io.prreviewassistant.review.config;

import io.prreviewassistant.github.client.GitHubApiClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RepositoryConfigProperties.class)
class RepositoryConfigConfiguration {

    @Bean
    RepositoryConfigLoader repositoryConfigLoader(
            GitHubApiClient client, RepositoryConfigProperties properties) {
        return new RepositoryConfigLoader(client, properties);
    }
}
