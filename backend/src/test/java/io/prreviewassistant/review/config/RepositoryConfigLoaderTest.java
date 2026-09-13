package io.prreviewassistant.review.config;

import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.client.GitHubApiClient;
import io.prreviewassistant.github.client.GitHubRepositoryFile;
import io.prreviewassistant.review.analysis.ReviewFindingCategory;
import io.prreviewassistant.review.retrieval.ChangedFile;
import io.prreviewassistant.review.retrieval.ChangedFileStatus;
import io.prreviewassistant.review.retrieval.PatchAvailability;
import io.prreviewassistant.review.retrieval.PullRequestSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

class RepositoryConfigLoaderTest {
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final int MAX_RESPONSE_BYTES = 139_264;
    private final GitHubApiClient client = mock(GitHubApiClient.class);
    private final RepositoryConfigProperties properties =
            new RepositoryConfigProperties(DataSize.ofKilobytes(32));
    private final RepositoryConfigLoader loader = new RepositoryConfigLoader(client, properties);

    @Test
    void fetchesCanonicalFileOnceFromBaseRepositoryAtExactBaseShaAndNeverHead() {
        byte[] content = "version: 1\nreview:\n  mode: fast\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(content, content.length));

        var result = loader.load(snapshot());

        assertThat(result.status()).isEqualTo(RepositoryConfigStatus.VALID);
        assertThat(result.effectiveConfig().mode()).isEqualTo(ReviewMode.FAST);
        verify(client).getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES);
        verify(client, never()).getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD,
                MAX_RESPONSE_BYTES);
    }

    @Test
    void headConfigCannotDisableSecurityOrChangeModeForItsOwnReview() {
        byte[] basePolicy = ("version: 1\nreview:\n  mode: balanced\ncategories:\n"
                + "  security: true\n").getBytes(StandardCharsets.UTF_8);
        byte[] hostileHeadPolicy = ("version: 1\nreview:\n  mode: fast\ncategories:\n"
                + "  security: false\n").getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(basePolicy, basePolicy.length));
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(hostileHeadPolicy, hostileHeadPolicy.length));

        var config = loader.load(snapshot()).effectiveConfig();

        assertThat(config.mode()).isEqualTo(ReviewMode.BALANCED);
        assertThat(config.enabledCategories()).contains(ReviewFindingCategory.SECURITY);
        verify(client, never()).getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD,
                MAX_RESPONSE_BYTES);
    }

    @Test
    void headConfigCannotDisableAllCategoriesOrIgnoreAllChangedFiles() {
        byte[] basePolicy = "version: 1\n".getBytes(StandardCharsets.UTF_8);
        byte[] hostileHeadPolicy = ("version: 1\nignore:\n  - \"**\"\ncategories:\n"
                + "  correctness: false\n  security: false\n  concurrency: false\n"
                + "  transactional_integrity: false\n  reliability: false\n"
                + "  api_misuse: false\n  performance: false\n").getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(basePolicy, basePolicy.length));
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(hostileHeadPolicy, hostileHeadPolicy.length));

        var config = loader.load(snapshot()).effectiveConfig();

        assertThat(config.enabledCategories()).hasSize(7);
        assertThat(config.ignorePatterns()).isEmpty();
        assertThat(config.filter(snapshotWithChangedFile()).changedFiles()).hasSize(1);
        verify(client, never()).getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD,
                MAX_RESPONSE_BYTES);
    }

    @Test
    void mergedPolicyAppliesOnlyWhenItBecomesTheLaterPullRequestsBaseRevision() {
        String laterHead = "c".repeat(40);
        byte[] balanced = "version: 1\nreview:\n  mode: balanced\n".getBytes(StandardCharsets.UTF_8);
        byte[] fast = "version: 1\nreview:\n  mode: fast\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(balanced, balanced.length));
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", HEAD, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(fast, fast.length));

        var firstPullRequest = loader.load(snapshot()).effectiveConfig();
        var laterPullRequest = loader.load(new PullRequestSnapshot(11, 22, "owner", "repo", 34,
                laterHead, HEAD, false, List.of())).effectiveConfig();

        assertThat(firstPullRequest.mode()).isEqualTo(ReviewMode.BALANCED);
        assertThat(laterPullRequest.mode()).isEqualTo(ReviewMode.FAST);
    }

    @Test
    void forkPullRequestUsesTrustedBaseRepositoryAndNeverContributorFork() {
        byte[] basePolicy = "version: 1\nreview:\n  mode: balanced\n".getBytes(StandardCharsets.UTF_8);
        var forkSnapshot = new PullRequestSnapshot(11, 22, "trusted-owner", "trusted-repo", 33,
                HEAD, BASE, false, List.of());
        when(client.getRepositoryFile(11, "trusted-owner", "trusted-repo", ".reviewbot.yml", BASE,
                MAX_RESPONSE_BYTES)).thenReturn(new GitHubRepositoryFile(basePolicy, basePolicy.length));

        assertThat(loader.load(forkSnapshot).effectiveConfig().mode()).isEqualTo(ReviewMode.BALANCED);
        verify(client).getRepositoryFile(11, "trusted-owner", "trusted-repo", ".reviewbot.yml", BASE,
                MAX_RESPONSE_BYTES);
        verify(client, never()).getRepositoryFile(11, "contributor", "fork", ".reviewbot.yml", HEAD,
                MAX_RESPONSE_BYTES);
    }

    @Test
    void pathNotFoundUsesDefaultsButAuthenticationAndTransientFailuresPropagate() {
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenThrow(GitHubException.resourceNotFound());
        var missing = loader.load(snapshot());
        assertThat(missing.status()).isEqualTo(RepositoryConfigStatus.DEFAULT_NOT_FOUND);
        assertThat(missing.effectiveConfig()).isEqualTo(EffectiveRepositoryReviewConfig.defaults());

        reset(client);
        doThrow(GitHubException.authenticationRejected()).when(client)
                .getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES);
        assertThatThrownBy(() -> loader.load(snapshot())).isInstanceOf(GitHubException.class);

        reset(client);
        doThrow(GitHubException.transientFailure()).when(client)
                .getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES);
        assertThatThrownBy(() -> loader.load(snapshot())).isInstanceOf(GitHubException.class);
    }

    @Test
    void invalidOrOversizedBasePolicyKeepsExistingSafeFallbackBehavior() {
        byte[] invalid = "version: 1\nunknown: rejected\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(invalid, invalid.length));
        assertThat(loader.load(snapshot()).status()).isEqualTo(RepositoryConfigStatus.INVALID);

        reset(client);
        byte[] small = "version: 1\n".getBytes(StandardCharsets.UTF_8);
        when(client.getRepositoryFile(11, "owner", "repo", ".reviewbot.yml", BASE, MAX_RESPONSE_BYTES))
                .thenReturn(new GitHubRepositoryFile(small, 32 * 1024 + 1));
        assertThat(loader.load(snapshot()).status()).isEqualTo(RepositoryConfigStatus.OVERSIZED);
    }

    private PullRequestSnapshot snapshot() {
        return new PullRequestSnapshot(11, 22, "owner", "repo", 33, HEAD,
                BASE, false, List.of());
    }

    private PullRequestSnapshot snapshotWithChangedFile() {
        return new PullRequestSnapshot(11, 22, "owner", "repo", 33, HEAD, BASE, false,
                List.of(new ChangedFile("src/Security.java", null, ChangedFileStatus.MODIFIED, 1, 0, 1,
                        PatchAvailability.AVAILABLE,
                        "@@ -1 +1 @@\n+secure")));
    }
}
