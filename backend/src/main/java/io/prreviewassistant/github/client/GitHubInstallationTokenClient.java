package io.prreviewassistant.github.client;

import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.prreviewassistant.github.auth.GitHubAppJwt;
import io.prreviewassistant.github.auth.GitHubAppJwtProvider;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.github.auth.InstallationTokenRequester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

public final class GitHubInstallationTokenClient implements InstallationTokenRequester {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    private final RestClient restClient;
    private final GitHubAppJwtProvider jwtProvider;

    public GitHubInstallationTokenClient(RestClient restClient, GitHubAppJwtProvider jwtProvider) {
        this.restClient = restClient;
        this.jwtProvider = jwtProvider;
    }

    @Override
    public InstallationAccessToken request(long installationId) {
        if (installationId <= 0) {
            throw GitHubException.invalidInstallationId();
        }
        GitHubAppJwt appJwt = jwtProvider.createJwt();
        String responseBody = GitHubHttpResponses.read(restClient.post()
                .uri("/app/installations/{installationId}/access_tokens", installationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + appJwt.value())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}"), true);
        return parse(responseBody);
    }

    private InstallationAccessToken parse(String responseBody) {
        try {
            JsonNode response = OBJECT_MAPPER.readTree(responseBody);
            JsonNode token = response.path("token");
            JsonNode expiresAt = response.path("expires_at");
            if (!token.isString() || token.stringValue().isBlank() || !expiresAt.isString()) {
                throw GitHubException.malformedResponse();
            }
            return new InstallationAccessToken(token.stringValue(), Instant.parse(expiresAt.stringValue()));
        } catch (RuntimeException exception) {
            if (exception instanceof GitHubException githubException) {
                throw githubException;
            }
            throw GitHubException.malformedResponse();
        }
    }
}
