package io.prreviewassistant.github.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import io.prreviewassistant.github.auth.InstallationAccessToken;
import org.junit.jupiter.api.Test;

class GitHubApiClientTest {

    @Test
    void listsInstallationAccessibleRepositoryCountWithInstallationToken() throws Exception {
        String tokenSecret = "installation-token-secret";
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "{\"total_count\":42,\"repositories\":[]}");
            GitHubApiClient client = new GitHubApiClient(
                    TestGitHubRestClients.create(server.baseUrl(), Duration.ofSeconds(2)),
                    installationId -> new InstallationAccessToken(
                            tokenSecret, Instant.parse("2026-09-02T11:00:00Z")));

            AccessibleRepositories repositories = client.listAccessibleRepositories(77);

            assertThat(repositories.totalCount()).isEqualTo(42);
            GitHubMockServer.RecordedRequest request = server.onlyRequest();
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri().getPath()).isEqualTo("/installation/repositories");
            assertThat(request.uri().getQuery()).isEqualTo("per_page=1");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + tokenSecret);
            assertThat(request.headers().getFirst("Accept")).isEqualTo(GitHubHttpConfiguration.ACCEPT);
            assertThat(request.headers().getFirst("X-GitHub-Api-Version"))
                    .isEqualTo(GitHubHttpConfiguration.API_VERSION);
        }
    }
}
