package io.prreviewassistant.review.config;

import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.client.GitHubApiClient;
import io.prreviewassistant.github.client.GitHubRepositoryFile;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class RepositoryConfigLoaderTest {
    private static final String HEAD = "a".repeat(40);
    private final GitHubApiClient client = mock(GitHubApiClient.class);
    private final RepositoryConfigProperties properties =
            new RepositoryConfigProperties(DataSize.ofKilobytes(32));
    private final RepositoryConfigLoader loader = new RepositoryConfigLoader(client, properties);

    @Test
    void fetchesCanonicalFileOnceAtExactHeadSha() {
        byte[] content = "version: 1\nreview:\n  mode: fast\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264))
                .thenReturn(new GitHubRepositoryFile(content, content.length));

        var result = loader.load(snapshot());

        assertThat(result.status()).isEqualTo(RepositoryConfigStatus.VALID);
        assertThat(result.effectiveConfig().mode()).isEqualTo(ReviewMode.FAST);
        verify(client).getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264);
    }

    @Test
    void pathNotFoundUsesDefaultsButAuthenticationAndTransientFailuresPropagate() {
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264))
                .thenThrow(GitHubException.resourceNotFound());
        assertThat(loader.load(snapshot()).status()).isEqualTo(RepositoryConfigStatus.DEFAULT_NOT_FOUND);

        reset(client);
        doThrow(GitHubException.authenticationRejected()).when(client)
                .getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264);
        assertThatThrownBy(() -> loader.load(snapshot())).isInstanceOf(GitHubException.class);

        reset(client);
        doThrow(GitHubException.transientFailure()).when(client)
                .getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264);
        assertThatThrownBy(() -> loader.load(snapshot())).isInstanceOf(GitHubException.class);
    }

    @Test
    void declaredOrActualOversizeFallsBackWithoutParsing() {
        byte[] small = "version: 1\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, 139_264))
                .thenReturn(new GitHubRepositoryFile(small, 32 * 1024 + 1));
        assertThat(loader.load(snapshot()).status()).isEqualTo(RepositoryConfigStatus.OVERSIZED);
    }

    private PullRequestSnapshot snapshot() {
        return new PullRequestSnapshot(11, 22, "owner", "repo", 33, HEAD,
                "b".repeat(40), false, List.of());
    }
}
