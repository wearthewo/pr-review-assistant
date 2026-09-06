package io.prreviewassistant.github.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.review.job.ReviewTarget;
import io.prreviewassistant.review.retrieval.PatchAvailability;
import io.prreviewassistant.review.retrieval.PullRequestFetchProperties;
import io.prreviewassistant.review.retrieval.PullRequestLoadResult;
import io.prreviewassistant.review.retrieval.PullRequestLoader;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PullRequestRetrievalContractTest {

    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);
    private static final ReviewTarget TARGET = new ReviewTarget(71, 99, 42, HEAD);

    @Test
    void retrievesAllPagesByDerivingTrustedRequestsAndReusesInstallationScopedProvider() throws Exception {
        AtomicInteger tokenRequests = new AtomicInteger();
        try (GitHubMockServer github = new GitHubMockServer();
                GitHubMockServer attacker = new GitHubMockServer()) {
            github.enqueue(200, repositoryJson());
            github.enqueue(200, pullRequestJson(HEAD));
            github.enqueue(200, """
                    [{"filename":"one.java","status":"modified","additions":2,"deletions":1,"changes":3,"patch":"@@ one"}]
                    """, Map.of("Link", "<" + attacker.baseUrl() + "/steal>; rel=\"next\""));
            github.enqueue(200, """
                    [{"filename":"image.bin","status":"added","additions":0,"deletions":0,"changes":0}]
                    """);

            PullRequestLoadResult result = loader(github, tokenRequests, 8192).load(TARGET);

            assertThat(result.outcome()).isEqualTo(PullRequestLoadResult.Outcome.READY);
            assertThat(result.snapshot().changedFiles()).hasSize(2);
            assertThat(result.snapshot().changedFiles().get(1).patchAvailability())
                    .isEqualTo(PatchAvailability.UNAVAILABLE);
            assertThat(github.requests()).extracting(request -> request.uri().toString())
                    .containsExactly(
                            "/repositories/99",
                            "/repos/octo/repo/pulls/42",
                            "/repos/octo/repo/pulls/42/files?per_page=100&page=1",
                            "/repos/octo/repo/pulls/42/files?per_page=100&page=2");
            assertThat(attacker.requests()).isEmpty();
            assertThat(tokenRequests).hasValue(4);
        }
    }

    @Test
    void stalePullRequestAvoidsChangedFileRequest() throws Exception {
        try (GitHubMockServer github = new GitHubMockServer()) {
            github.enqueue(200, repositoryJson());
            github.enqueue(200, pullRequestJson("c".repeat(40)));

            PullRequestLoadResult result = loader(github, new AtomicInteger(), 8192).load(TARGET);

            assertThat(result.outcome()).isEqualTo(PullRequestLoadResult.Outcome.STALE);
            assertThat(github.requests()).hasSize(2);
        }
    }

    @Test
    void operationSpecificPageResponseLimitProducesTooLargeOutcome() throws Exception {
        try (GitHubMockServer github = new GitHubMockServer()) {
            github.enqueue(200, repositoryJson());
            github.enqueue(200, pullRequestJson(HEAD));
            github.enqueue(200, "x".repeat(1025));

            PullRequestLoadResult result = loader(github, new AtomicInteger(), 1024).load(TARGET);

            assertThat(result.outcome()).isEqualTo(PullRequestLoadResult.Outcome.TOO_LARGE);
            assertThat(result.snapshot()).isNull();
        }
    }

    private PullRequestLoader loader(GitHubMockServer server, AtomicInteger tokenRequests, int responseBytes) {
        GitHubApiClient client = new GitHubApiClient(
                TestGitHubRestClients.create(server.baseUrl(), Duration.ofSeconds(2)),
                installationId -> {
                    assertThat(installationId).isEqualTo(71);
                    tokenRequests.incrementAndGet();
                    return new InstallationAccessToken(
                            "opaque-secret", Instant.parse("2026-09-06T16:00:00Z"));
                });
        PullRequestFetchProperties properties = new PullRequestFetchProperties(
                1000, DataSize.ofKilobytes(256), DataSize.ofMegabytes(5), 10,
                DataSize.ofBytes(responseBytes));
        return new PullRequestLoader(client, properties);
    }

    private String repositoryJson() {
        return "{\"id\":99,\"name\":\"repo\",\"owner\":{\"login\":\"octo\"}}";
    }

    private String pullRequestJson(String head) {
        return "{\"number\":42,\"draft\":false,\"head\":{\"sha\":\"" + head
                + "\"},\"base\":{\"sha\":\"" + BASE + "\",\"repo\":{\"id\":99}}}";
    }
}
