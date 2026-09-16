package io.prreviewassistant.dashboard.github;

import java.net.http.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubConnectionProperties.class)
class GitHubConnectionConfiguration {

    @Bean
    RestClient gitHubUserOAuthRestClient(GitHubConnectionProperties properties) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(java.time.Duration.ofSeconds(15));
        return RestClient.builder()
                .baseUrl(properties.oauthBaseUrl().toString().replaceAll("/+$", ""))
                .requestFactory(factory).build();
    }

    @Bean
    GitHubUserAuthorizationClient gitHubUserAuthorizationClient(
            RestClient gitHubUserOAuthRestClient,
            @Qualifier("gitHubRestClient") RestClient gitHubRestClient,
            ObjectMapper objectMapper,
            GitHubConnectionProperties properties) {
        return new RestGitHubUserAuthorizationClient(
                gitHubUserOAuthRestClient, gitHubRestClient, objectMapper, properties);
    }
}
