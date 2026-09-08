package io.prreviewassistant.github.client;

import io.prreviewassistant.github.auth.InstallationAccessToken;
import io.prreviewassistant.github.auth.InstallationTokenProvider;
import io.prreviewassistant.review.publication.PublicationComment;
import io.prreviewassistant.review.publication.PublicationPayload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class GitHubReviewClientTest {
    @Test void createsOneCommentReviewAtExactCommitWithModernCoordinates() throws Exception {
        try(GitHubMockServer server=new GitHubMockServer()){
            server.enqueue(200,"{\"id\":42,\"submitted_at\":\"2026-09-08T10:00:00Z\"}");
            GitHubReviewClient client=client(server);
            var result=client.create(7,"octo","repo",12,"a".repeat(40),new PublicationPayload(1,"summary",List.of(
                    new PublicationComment("src/A.java",12,10,"finding"))),4096);
            var request=server.onlyRequest();
            assertThat(result.id()).isEqualTo(42); assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri().toString()).isEqualTo("/repos/octo/repo/pulls/12/reviews");
            assertThat(request.headers().getFirst("Authorization")).isEqualTo("Bearer opaque-secret");
            assertThat(request.headers().getFirst("Accept")).isEqualTo("application/vnd.github+json");
            assertThat(request.headers().getFirst("X-Github-Api-Version")).isEqualTo("2026-03-10");
            assertThat(request.body()).contains("\"commit_id\":\""+"a".repeat(40)+"\"","\"event\":\"COMMENT\"",
                    "\"line\":12","\"side\":\"RIGHT\"","\"start_line\":10","\"start_side\":\"RIGHT\"")
                    .doesNotContain("position");
        }
    }
    @Test void listsReviewsWithBoundedDerivedPagination() throws Exception {
        try(GitHubMockServer server=new GitHubMockServer()){
            server.enqueue(200,"[{\"id\":9,\"body\":\"marker\",\"submitted_at\":\"2026-09-08T10:00:00Z\"}]",
                    java.util.Map.of("Link","<https://evil.example/exfiltrate>; rel=\"next\""));
            GitHubReviewPage page=client(server).list(7,"octo","repo",12,1,4096);
            assertThat(page.hasNextPage()).isTrue(); assertThat(server.onlyRequest().uri().getHost()).isNull();
            assertThat(server.onlyRequest().uri().toString()).isEqualTo("/repos/octo/repo/pulls/12/reviews?per_page=100&page=1");
        }
    }
    @Test void mapsWriteFailuresWithoutLeakingResponseBodyOrToken() throws Exception {
        try(GitHubMockServer server=new GitHubMockServer()){
            server.enqueue(422,"fake-secret-in-body");
            assertThatThrownBy(()->client(server).create(7,"octo","repo",12,"a".repeat(40),
                    new PublicationPayload(1,"summary",List.of()),4096))
                    .isInstanceOf(GitHubReviewException.class)
                    .hasMessageNotContaining("fake-secret-in-body").hasMessageNotContaining("opaque-secret");
        }
    }
    @ParameterizedTest
    @CsvSource({
            "401,AUTHENTICATION",
            "403,PERMISSION",
            "404,NOT_FOUND",
            "429,RATE_LIMITED",
            "500,TRANSIENT"
    })
    void classifiesGitHubReviewFailures(int status, GitHubReviewErrorType expected) throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(status, "untrusted-response-body");

            assertThatThrownBy(() -> client(server).create(7, "octo", "repo", 12, "a".repeat(40),
                    new PublicationPayload(1, "summary", List.of()), 4_096))
                    .isInstanceOfSatisfying(GitHubReviewException.class,
                            failure -> assertThat(failure.type()).isEqualTo(expected))
                    .hasMessageNotContaining("untrusted-response-body")
                    .hasMessageNotContaining("opaque-secret");
        }
    }
    @Test void recognizesHeaderIdentifiedSecondaryLimitOnValidationStatus() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(422, "untrusted-response-body", java.util.Map.of("Retry-After", "30"));

            assertThatThrownBy(() -> client(server).create(7, "octo", "repo", 12, "a".repeat(40),
                    new PublicationPayload(1, "summary", List.of()), 4_096))
                    .isInstanceOfSatisfying(GitHubReviewException.class,
                            failure -> assertThat(failure.type()).isEqualTo(GitHubReviewErrorType.RATE_LIMITED));
        }
    }
    @Test void malformedOrOversizedSuccessfulWriteResponseIsSafeAndExplicit() throws Exception {
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "{not-json");
            assertThatThrownBy(() -> client(server).create(7, "octo", "repo", 12, "a".repeat(40),
                    new PublicationPayload(1, "summary", List.of()), 4_096))
                    .isInstanceOfSatisfying(GitHubReviewException.class,
                            failure -> assertThat(failure.type()).isEqualTo(GitHubReviewErrorType.MALFORMED_RESPONSE));
        }
        try (GitHubMockServer server = new GitHubMockServer()) {
            server.enqueue(200, "x".repeat(4_097));
            assertThatThrownBy(() -> client(server).create(7, "octo", "repo", 12, "a".repeat(40),
                    new PublicationPayload(1, "summary", List.of()), 4_096))
                    .isInstanceOfSatisfying(GitHubReviewException.class,
                            failure -> assertThat(failure.type()).isEqualTo(GitHubReviewErrorType.RESPONSE_TOO_LARGE));
        }
    }
    private GitHubReviewClient client(GitHubMockServer server){InstallationTokenProvider provider=id->new InstallationAccessToken(
            "opaque-secret",Instant.now().plusSeconds(3600));return new GitHubReviewClient(
                    TestGitHubRestClients.create(server.baseUrl(),Duration.ofSeconds(2)),provider);}
}
