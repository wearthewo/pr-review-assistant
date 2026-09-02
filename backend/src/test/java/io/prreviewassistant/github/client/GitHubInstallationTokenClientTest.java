package io.prreviewassistant.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import io.prreviewassistant.github.auth.GitHubAppJwt;
import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationAccessToken;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitHubInstallationTokenClientTest {

    private static final String APP_JWT = "synthetic-app-jwt-secret";
    private static final String INSTALLATION_TOKEN = "opaque-token-with-no-assumed-shape";

    @Test
    void requestsAndMapsInstallationTokenWithRequiredHeaders() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(201, "{\"token\":\"" + INSTALLATION_TOKEN
                    + "\",\"expires_at\":\"2026-09-02T11:00:00Z\"}");

            InstallationAccessToken token = client(server).request(987654321L);

            assertThat(token.value()).isEqualTo(INSTALLATION_TOKEN);
            assertThat(token.expiresAt()).hasToString("2026-09-02T11:00:00Z");
            GitHubMockServer.RecordedRequest request = server.onlyRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri().getPath())
                    .isEqualTo("/app/installations/987654321/access_tokens");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + APP_JWT);
            assertThat(request.headers().getFirst("Accept")).isEqualTo(GitHubHttpConfiguration.ACCEPT);
            assertThat(request.headers().getFirst("X-GitHub-Api-Version"))
                    .isEqualTo(GitHubHttpConfiguration.API_VERSION);
        }
    }

    @Test
    void mapsUnauthorizedWithoutExposingJwt() throws Exception {
        assertError(401, MapHeaders.none(), GitHubErrorType.AUTHENTICATION_REJECTED);
    }

    @Test
    void mapsMissingInstallation() throws Exception {
        assertError(404, MapHeaders.none(), GitHubErrorType.INSTALLATION_NOT_FOUND);
    }

    @Test
    void mapsRateLimitResponse() throws Exception {
        assertError(403, MapHeaders.rateLimited(), GitHubErrorType.RATE_LIMITED);
    }

    @Test
    void mapsServerFailureAsTransient() throws Exception {
        assertError(503, MapHeaders.none(), GitHubErrorType.TRANSIENT_FAILURE);
    }

    @Test
    void rejectsMalformedResponseWithoutExposingToken() throws Exception {
        String malformedSecret = "response-secret-must-not-leak";
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(201, "{\"token\":\"" + malformedSecret + "\",\"expires_at\":false}");

            assertThatThrownBy(() -> client(server).request(11))
                    .isInstanceOfSatisfying(GitHubException.class, exception -> {
                        assertThat(exception.type()).isEqualTo(GitHubErrorType.MALFORMED_RESPONSE);
                        assertThat(exception.toString()).doesNotContain(malformedSecret, APP_JWT);
                    });
        }
    }

    @Test
    void rejectsOversizedResponse() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(201, "x".repeat(64 * 1024 + 1));

            assertThatThrownBy(() -> client(server).request(11))
                    .isInstanceOfSatisfying(GitHubException.class,
                            exception -> assertThat(exception.type())
                                    .isEqualTo(GitHubErrorType.MALFORMED_RESPONSE));
        }
    }

    private void assertError(int status, java.util.Map<String, String> headers, GitHubErrorType type)
            throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(status, "{\"message\":\"remote-response-must-not-leak\"}", headers);

            assertThatThrownBy(() -> client(server).request(11))
                    .isInstanceOfSatisfying(GitHubException.class, exception -> {
                        assertThat(exception.type()).isEqualTo(type);
                        assertThat(exception.toString()).doesNotContain(APP_JWT, "remote-response-must-not-leak");
                    });
        }
    }

    private GitHubInstallationTokenClient client(GitHubMockServer server) {
        RestClient restClient = TestGitHubRestClients.create(server.baseUrl(), Duration.ofSeconds(2));
        return new GitHubInstallationTokenClient(restClient, () -> new GitHubAppJwt(APP_JWT));
    }

    private static final class MapHeaders {
        private MapHeaders() {
        }

        static java.util.Map<String, String> none() {
            return java.util.Map.of();
        }

        static java.util.Map<String, String> rateLimited() {
            return java.util.Map.of("X-RateLimit-Remaining", "0");
        }
    }
}
