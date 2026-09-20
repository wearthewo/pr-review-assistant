package io.prreviewassistant.github.client;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import io.prreviewassistant.observability.ApplicationMetrics;
import io.prreviewassistant.observability.ApplicationMetrics.GitHubOperation;

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
    private final ApplicationMetrics metrics;
    private final Clock clock;

    public GitHubInstallationTokenClient(RestClient restClient, GitHubAppJwtProvider jwtProvider) {
        this(restClient, jwtProvider, ApplicationMetrics.noop(), Clock.systemUTC());
    }

    public GitHubInstallationTokenClient(RestClient restClient, GitHubAppJwtProvider jwtProvider,
            ApplicationMetrics metrics, Clock clock) {
        this.restClient = restClient;
        this.jwtProvider = jwtProvider;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public InstallationAccessToken request(long installationId) {
        Instant startedAt = clock.instant();
        try {
            InstallationAccessToken result = requestToken(installationId);
            metrics.github(GitHubOperation.INSTALLATION_TOKEN, "success",
                    Duration.between(startedAt, clock.instant()));
            return result;
        } catch (GitHubException exception) {
            metrics.github(GitHubOperation.INSTALLATION_TOKEN,
                    exception.type().name().toLowerCase(java.util.Locale.ROOT),
                    Duration.between(startedAt, clock.instant()));
            throw exception;
        } catch (RuntimeException exception) {
            metrics.github(GitHubOperation.INSTALLATION_TOKEN, "unexpected_failure",
                    Duration.between(startedAt, clock.instant()));
            throw exception;
        }
    }

    private InstallationAccessToken requestToken(long installationId) {
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
