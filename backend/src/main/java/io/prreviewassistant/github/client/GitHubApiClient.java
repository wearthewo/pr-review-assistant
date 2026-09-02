package io.prreviewassistant.github.client;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.github.auth.InstallationTokenProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

public final class GitHubApiClient {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final InstallationTokenProvider tokenProvider;

    public GitHubApiClient(RestClient restClient, InstallationTokenProvider tokenProvider) {
        this.restClient = restClient;
        this.tokenProvider = tokenProvider;
    }

    public AccessibleRepositories listAccessibleRepositories(long installationId) {
        InstallationAccessToken token = tokenProvider.tokenFor(installationId);
        String responseBody;
        responseBody = GitHubHttpResponses.read(restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/installation/repositories")
                        .queryParam("per_page", 1)
                        .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), false);
        return parse(responseBody);
    }

    private AccessibleRepositories parse(String responseBody) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            JsonNode totalCount = response.path("total_count");
            if (!totalCount.isIntegralNumber() || !totalCount.canConvertToLong() || totalCount.longValue() < 0) {
                throw GitHubException.malformedResponse();
            }
            return new AccessibleRepositories(totalCount.longValue());
        } catch (RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.malformedResponse();
        }
    }
}
