package io.prreviewassistant.github.client;

import java.net.http.HttpClient;
import java.time.Clock;

import io.prreviewassistant.github.auth.CachingInstallationTokenProvider;
import io.prreviewassistant.github.auth.GitHubAppJwtService;
import io.prreviewassistant.github.auth.GitHubAppJwtProvider;
import io.prreviewassistant.github.auth.GitHubAppProperties;
import io.prreviewassistant.github.auth.InstallationTokenProvider;
import io.prreviewassistant.github.auth.InstallationTokenRequester;
import io.prreviewassistant.github.auth.PemPrivateKeyLoader;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.prreviewassistant.observability.ApplicationMetrics;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubAppProperties.class)
class GitHubHttpConfiguration {

    static final String ACCEPT = "application/vnd.github+json";
    static final String API_VERSION = "2026-03-10";

    @Bean
    Clock githubClock() {
        return Clock.systemUTC();
    }

    @Bean
    PemPrivateKeyLoader pemPrivateKeyLoader() {
        return new PemPrivateKeyLoader();
    }

    @Bean
    GitHubAppJwtService gitHubAppJwtService(
            GitHubAppProperties properties,
            PemPrivateKeyLoader privateKeyLoader,
            Clock githubClock) {
        return new GitHubAppJwtService(properties, privateKeyLoader, githubClock);
    }

    @Bean
    RestClient gitHubRestClient(GitHubAppProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.requestTimeout());

        String baseUrl = properties.apiBaseUrl().toString().replaceAll("/+$", "");
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", ACCEPT)
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .build();
    }

    @Bean
    InstallationTokenRequester installationTokenRequester(
            RestClient gitHubRestClient,
            GitHubAppJwtProvider jwtProvider,
            ApplicationMetrics metrics,
            Clock githubClock) {
        return new GitHubInstallationTokenClient(gitHubRestClient, jwtProvider, metrics, githubClock);
    }

    @Bean
    InstallationTokenProvider installationTokenProvider(
            InstallationTokenRequester requester,
            Clock githubClock,
            GitHubAppProperties properties) {
        return new CachingInstallationTokenProvider(requester, githubClock, properties.tokenRefreshSkew());
    }

    @Bean
    GitHubApiClient gitHubApiClient(
            RestClient gitHubRestClient,
            InstallationTokenProvider tokenProvider,
            ApplicationMetrics metrics,
            Clock githubClock) {
        return new GitHubApiClient(gitHubRestClient, tokenProvider, metrics, githubClock);
    }

    @Bean
    GitHubReviewClient gitHubReviewClient(RestClient gitHubRestClient, InstallationTokenProvider tokenProvider,
            ApplicationMetrics metrics, Clock githubClock) {
        return new GitHubReviewClient(gitHubRestClient, tokenProvider, metrics, githubClock);
    }
}
