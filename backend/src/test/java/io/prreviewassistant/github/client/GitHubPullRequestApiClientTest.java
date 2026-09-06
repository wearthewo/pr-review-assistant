package io.prreviewassistant.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Base64;

import io.prreviewassistant.github.auth.GitHubErrorType;
import io.prreviewassistant.github.auth.GitHubException;
import io.prreviewassistant.github.auth.InstallationAccessToken;
import org.junit.jupiter.api.Test;

class GitHubPullRequestApiClientTest {

    private static final String TOKEN = "opaque-installation-secret";
    private static final String HEAD = "a".repeat(40);
    private static final String BASE = "b".repeat(40);

    @Test
    void resolvesRepositoryByNumericIdWithInstallationAuthenticationAndSharedHeaders() throws Exception {
        AtomicLong requestedInstallation = new AtomicLong();
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, repositoryJson());
            GitHubApiClient client = client(server, requestedInstallation);

            GitHubRepositoryMetadata repository = client.getRepository(71, 99, 4096);

            assertThat(repository).isEqualTo(new GitHubRepositoryMetadata(99, "octo-org", "safe-repo"));
            assertThat(requestedInstallation).hasValue(71);
            assertRequest(server.onlyRequest(), "/repositories/99", null);
        }
    }

    @Test
    void retrievesAndParsesNarrowPullRequestMetadata() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, pullRequestJson(42, 99, HEAD));

            GitHubPullRequestMetadata pullRequest = client(server, new AtomicLong())
                    .getPullRequest(71, "octo-org", "safe-repo", 42, 4096);

            assertThat(pullRequest).isEqualTo(new GitHubPullRequestMetadata(42, 99, HEAD, BASE, false));
            assertRequest(server.onlyRequest(), "/repos/octo-org/safe-repo/pulls/42", null);
        }
    }

    @Test
    void parsesFilesIncludingMissingPatchRenameRemovalUnknownStatusAndUnicodePath() throws Exception {
        String body = """
                [
                  {"filename":"src/üñîçødé.java","status":"modified","additions":2,"deletions":1,"changes":3,"patch":"@@ fake instruction"},
                  {"filename":"new.txt","previous_filename":"old.txt","status":"renamed","additions":0,"deletions":0,"changes":0},
                  {"filename":"gone.bin","status":"removed","additions":0,"deletions":4,"changes":4},
                  {"filename":"future.file","status":"future_status","additions":1,"deletions":0,"changes":1}
                ]
                """;
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, body);

            GitHubChangedFilePage page = client(server, new AtomicLong())
                    .listPullRequestFiles(71, "octo-org", "safe-repo", 42, 3, 100, 16 * 1024);

            assertThat(page.files()).hasSize(4);
            assertThat(page.files().get(0).path()).isEqualTo("src/üñîçødé.java");
            assertThat(page.files().get(0).patch()).isEqualTo("@@ fake instruction");
            assertThat(page.files().get(1).previousPath()).isEqualTo("old.txt");
            assertThat(page.files().get(1).patch()).isNull();
            assertThat(page.files().get(2).status()).isEqualTo("removed");
            assertThat(page.files().get(3).status()).isEqualTo("future_status");
            assertThat(page.hasNextPage()).isFalse();
            assertRequest(server.onlyRequest(), "/repos/octo-org/safe-repo/pulls/42/files",
                    "per_page=100&page=3");
        }
    }

    @Test
    void reportsNextPageFromLinkButNeverFollowsTheResponseUrl() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "[]", Map.of("Link",
                    "<https://attacker.invalid/steal?page=2>; rel=\"next\""));

            GitHubChangedFilePage page = client(server, new AtomicLong())
                    .listPullRequestFiles(71, "octo-org", "safe-repo", 42, 1, 100, 4096);

            assertThat(page.hasNextPage()).isTrue();
            assertThat(server.requests()).hasSize(1);
        }
    }

    @Test
    void authenticatedRedirectToAnotherHostIsNotFollowed() throws Exception {
        try (GitHubMockServer source = new GitHubMockServer();
                GitHubMockServer attacker = new GitHubMockServer()) {
            source.enqueue(302, "redirect-body-secret", Map.of("Location", attacker.baseUrl().toString()));

            assertThatThrownBy(() -> client(source, new AtomicLong()).getRepository(71, 99, 4096))
                    .isInstanceOfSatisfying(GitHubException.class, exception -> {
                        assertThat(exception.type()).isEqualTo(GitHubErrorType.AUTHENTICATION_REJECTED);
                        assertThat(exception.toString()).doesNotContain(TOKEN, "redirect-body-secret");
                    });
            assertThat(attacker.requests()).isEmpty();
        }
    }

    @Test
    void rejectsMalformedAndOversizedSuccessfulResponsesWithoutLeakingBodies() throws Exception {
        try (GitHubMockServer malformed = new GitHubMockServer();
                GitHubMockServer oversized = new GitHubMockServer()) {
            malformed.enqueue(200, "{\"id\":\"response-secret\"}");
            oversized.enqueue(200, "x".repeat(1025));

            assertError(() -> client(malformed, new AtomicLong()).getRepository(71, 99, 4096),
                    GitHubErrorType.MALFORMED_RESPONSE, "response-secret");
            assertError(() -> client(oversized, new AtomicLong()).getRepository(71, 99, 1024),
                    GitHubErrorType.RESPONSE_TOO_LARGE, "x".repeat(32));
        }
    }

    @Test
    void classifiesAuthenticationRateLimitMissingResourceAndTransientFailures() throws Exception {
        assertHttpError(401, Map.of(), GitHubErrorType.AUTHENTICATION_REJECTED);
        assertHttpError(403, Map.of(), GitHubErrorType.AUTHENTICATION_REJECTED);
        assertHttpError(403, Map.of("X-RateLimit-Remaining", "0"), GitHubErrorType.RATE_LIMITED);
        assertHttpError(403, Map.of("Retry-After", "60"), GitHubErrorType.RATE_LIMITED);
        assertHttpError(404, Map.of(), GitHubErrorType.RESOURCE_NOT_FOUND);
        assertHttpError(429, Map.of("Retry-After", "60"), GitHubErrorType.RATE_LIMITED);
        assertHttpError(503, Map.of(), GitHubErrorType.TRANSIENT_FAILURE);
    }

    @Test
    void classifiesNetworkTimeoutAsTransientWithoutLeakingAuthorization() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueueBlocked(entered, release);
            GitHubApiClient client = new GitHubApiClient(
                    TestGitHubRestClients.create(server.baseUrl(), Duration.ofMillis(100)),
                    installationId -> new InstallationAccessToken(
                            TOKEN, Instant.parse("2026-09-06T15:00:00Z")));
            try {
                assertError(() -> client.getRepository(71, 99, 4096),
                        GitHubErrorType.TRANSIENT_FAILURE, TOKEN);
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void changedFileProviderValueDoesNotExposePathOrPatchThroughToString() {
        GitHubChangedFileData file = new GitHubChangedFileData(
                "../../secret-path", null, "modified", 1, 1, 2, "fake-token-in-patch");

        assertThat(file.toString()).doesNotContain("../../secret-path", "fake-token-in-patch");
    }

    @Test
    void retrievesRepositoryFileAtExactImmutableRevisionAndRedactsContent() throws Exception {
        String source = "secret-shaped-source";
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "{\"type\":\"file\",\"encoding\":\"base64\",\"size\":"
                    + source.length() + ",\"content\":\""
                    + Base64.getEncoder().encodeToString(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "\"}");
            GitHubRepositoryFile file = client(server, new AtomicLong()).getRepositoryFile(
                    71, "octo-org", "safe-repo", "src/unicode-λ.java", HEAD, 4096);
            assertThat(new String(file.content(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(source);
            assertThat(file.toString()).doesNotContain(source);
            assertRequest(server.onlyRequest(), "/repos/octo-org/safe-repo/contents/src/unicode-λ.java", "ref=" + HEAD);
        }
    }

    @Test
    void rejectsMalformedContentAndUnsafePathWithoutLeakingSource() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "{\"type\":\"file\",\"encoding\":\"base64\",\"size\":2,\"content\":\"%%%fake-secret\"}");
            assertError(() -> client(server, new AtomicLong()).getRepositoryFile(
                    71, "octo-org", "safe-repo", "src/A.java", HEAD, 4096),
                    GitHubErrorType.MALFORMED_RESPONSE, "fake-secret");
        }
        try (GitHubMockServer server = new GitHubMockServer()) {
            assertError(() -> client(server, new AtomicLong()).getRepositoryFile(
                    71, "octo-org", "safe-repo", "../../etc/passwd", HEAD, 4096),
                    GitHubErrorType.MALFORMED_RESPONSE, "passwd");
            assertThat(server.requests()).isEmpty();
        }
    }

    private void assertHttpError(int status, Map<String, String> headers, GitHubErrorType expected)
            throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(status, "{\"message\":\"fake-secret-error-body\"}", headers);
            assertError(() -> client(server, new AtomicLong()).getRepository(71, 99, 4096),
                    expected, "fake-secret-error-body");
        }
    }

    private void assertError(ThrowingCall call, GitHubErrorType expected, String forbidden) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(GitHubException.class, exception -> {
                    assertThat(exception.type()).isEqualTo(expected);
                    assertThat(exception.toString()).doesNotContain(TOKEN, forbidden);
                });
    }

    private GitHubApiClient client(GitHubMockServer server, AtomicLong requestedInstallation) {
        return new GitHubApiClient(
                TestGitHubRestClients.create(server.baseUrl(), Duration.ofSeconds(2)),
                installationId -> {
                    requestedInstallation.set(installationId);
                    return new InstallationAccessToken(TOKEN, Instant.parse("2026-09-06T15:00:00Z"));
                });
    }

    private void assertRequest(GitHubMockServer.RecordedRequest request, String path, String query) {
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.uri().getPath()).isEqualTo(path);
        assertThat(request.uri().getQuery()).isEqualTo(query);
        assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(request.headers().getFirst("Accept")).isEqualTo(GitHubHttpConfiguration.ACCEPT);
        assertThat(request.headers().getFirst("X-GitHub-Api-Version"))
                .isEqualTo(GitHubHttpConfiguration.API_VERSION);
    }

    private String repositoryJson() {
        return "{\"id\":99,\"name\":\"safe-repo\",\"owner\":{\"login\":\"octo-org\"}}";
    }

    private String pullRequestJson(int number, long repositoryId, String head) {
        return "{\"number\":" + number
                + ",\"draft\":false,\"head\":{\"sha\":\"" + head
                + "\"},\"base\":{\"sha\":\"" + BASE
                + "\",\"repo\":{\"id\":" + repositoryId + "}}}";
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }
}
