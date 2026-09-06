package io.prreviewassistant.github.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

final class TestGitHubRestClients {

    private TestGitHubRestClients() {
    }

    static RestClient create(URI baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return RestClient.builder()
                .baseUrl(baseUrl.toString())
                .requestFactory(requestFactory)
                .defaultHeader("Accept", GitHubHttpConfiguration.ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", GitHubHttpConfiguration.API_VERSION)
                .build();
    }
}
