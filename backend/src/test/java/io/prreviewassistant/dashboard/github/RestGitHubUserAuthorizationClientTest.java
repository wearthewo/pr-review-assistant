package io.prreviewassistant.dashboard.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class RestGitHubUserAuthorizationClientTest {
    @Test
    void exchangesCodeVerifiesStableUserAndReadsBoundedPagination() throws Exception {
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\",\"refresh_token\":\"ignored\"}", Map.of());
            api.enqueue(200, "{\"id\":77,\"login\":\"renameable\",\"email\":\"ignored@example.com\"}", Map.of());
            api.enqueue(200, "{\"installations\":[{\"id\":123,\"app_id\":1,\"target_type\":\"User\",\"account\":{\"id\":77,\"type\":\"User\"}}]}",
                    Map.of("Link", "<https://attacker.example/steal>; rel=\"next\""));
            api.enqueue(200, "{\"installations\":[]}", Map.of());
            GitHubConnectionProof proof = client(oauth, api, 10).verify("one-time-code", "v".repeat(43));

            assertThat(proof.userId()).isEqualTo(77);
            assertThat(proof.installations()).extracting(GitHubConnectionProof.AccessibleInstallation::installationId)
                    .containsExactly(123L);
            assertThat(proof.installations()).extracting(GitHubConnectionProof.AccessibleInstallation::appId)
                    .containsExactly(1L);
            assertThat(oauth.requests().getFirst().path()).isEqualTo("/login/oauth/access_token");
            assertThat(oauth.requests().getFirst().body()).contains("code=one-time-code", "code_verifier=" + "v".repeat(43));
            assertThat(api.requests()).extracting(Request::path).containsExactly(
                    "/user", "/user/installations?per_page=100&page=1", "/user/installations?per_page=100&page=2");
            assertThat(api.requests()).allSatisfy(request -> {
                assertThat(request.authorization()).isEqualTo("Bearer secret-user-token");
                assertThat(request.apiVersion()).isEqualTo("2026-03-10");
            });
            assertThat(api.requests()).noneSatisfy(request -> assertThat(request.host()).isEqualTo("attacker.example"));
        }
    }

    @Test
    void rejectsAuthenticationRateLimitMalformedOversizedAndPageCeilingWithoutLeakingToken() throws Exception {
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(200, "{\"id\":77}", Map.of());
            api.enqueue(403, "provider-secret-body", Map.of("X-RateLimit-Remaining", "0"));
            assertThatThrownBy(() -> client(oauth, api, 10).verify("code", "v".repeat(43)))
                    .isInstanceOf(GitHubConnectionException.class)
                    .hasToString("GitHubConnectionException{error=GITHUB_RATE_LIMITED}")
                    .hasMessageNotContaining("secret-user-token")
                    .hasMessageNotContaining("provider-secret-body");
        }
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(200, "{\"id\":77}", Map.of());
            api.enqueue(200, "{\"installations\":\"wrong\"}", Map.of());
            assertThatThrownBy(() -> client(oauth, api, 10).verify("code", "v".repeat(43)))
                    .isInstanceOf(GitHubConnectionException.class)
                    .hasToString("GitHubConnectionException{error=GITHUB_RESPONSE_INVALID}");
        }
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(200, "{\"id\":77}", Map.of());
            api.enqueue(200, "{\"installations\":[]}", Map.of("Link", "<https://api.github.com/page/2>; rel=\"next\""));
            assertThatThrownBy(() -> client(oauth, api, 1).verify("code", "v".repeat(43)))
                    .isInstanceOf(GitHubConnectionException.class)
                    .hasToString("GitHubConnectionException{error=TOO_MANY_INSTALLATIONS}");
        }
    }

    @Test
    void classifiesTokenUserAndInstallationFailuresWithoutReturningProviderBodies() throws Exception {
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(401, "exchange-secret", Map.of());
            assertSafeError(client(oauth, api, 10), GitHubConnectionError.GITHUB_AUTHENTICATION_REJECTED,
                    "exchange-secret");
        }
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(500, "user-api-secret", Map.of());
            assertSafeError(client(oauth, api, 10), GitHubConnectionError.GITHUB_UNAVAILABLE,
                    "user-api-secret", "secret-user-token");
        }
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(200, "{\"id\":77}", Map.of());
            api.enqueue(401, "installation-api-secret", Map.of());
            assertSafeError(client(oauth, api, 10), GitHubConnectionError.GITHUB_AUTHENTICATION_REJECTED,
                    "installation-api-secret", "secret-user-token");
        }
        try (MockServer oauth = new MockServer(); MockServer api = new MockServer()) {
            oauth.enqueue(200, "{\"access_token\":\"secret-user-token\"}", Map.of());
            api.enqueue(200, "{\"padding\":\"" + "x".repeat(2048) + "\"}", Map.of());
            assertSafeError(client(oauth, api, 10, 1024), GitHubConnectionError.GITHUB_RESPONSE_INVALID,
                    "secret-user-token");
        }
    }

    private static void assertSafeError(RestGitHubUserAuthorizationClient client, GitHubConnectionError expected,
            String... forbidden) {
        assertThatThrownBy(() -> client.verify("code", "v".repeat(43)))
                .isInstanceOf(GitHubConnectionException.class)
                .satisfies(error -> {
                    assertThat(((GitHubConnectionException) error).error()).isEqualTo(expected);
                    assertThat(error.toString()).doesNotContain(forbidden);
                });
    }

    private static RestGitHubUserAuthorizationClient client(MockServer oauth, MockServer api, int maxPages) {
        return client(oauth, api, maxPages, 262144);
    }

    private static RestGitHubUserAuthorizationClient client(
            MockServer oauth, MockServer api, int maxPages, int maxResponseBytes) {
        GitHubConnectionProperties properties = new GitHubConnectionProperties("1", "client", "secret",
                URI.create("https://app.example/github/callback"), oauth.baseUrl(), Duration.ofMinutes(10),
                5, maxPages, 1000, maxResponseBytes);
        return new RestGitHubUserAuthorizationClient(rest(oauth.baseUrl()), rest(api.baseUrl()),
                new ObjectMapper(), properties);
    }

    private static RestClient rest(URI baseUrl) {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(baseUrl.toString()).requestFactory(factory)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2026-03-10").build();
    }

    private static final class MockServer implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentLinkedQueue<Response> responses = new ConcurrentLinkedQueue<>();
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        MockServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle); server.start();
        }
        URI baseUrl() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        void enqueue(int status, String body, Map<String, String> headers) { responses.add(new Response(status, body, headers)); }
        List<Request> requests() { return List.copyOf(requests); }
        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new Request(exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Response response = responses.remove();
            response.headers().forEach((key, value) -> exchange.getResponseHeaders().add(key, value));
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }
        @Override public void close() { server.stop(0); }
    }

    private record Request(String path, String host, String authorization, String apiVersion, String body) { }
    private record Response(int status, String body, Map<String, String> headers) { }
}
