package io.prreviewassistant.dashboard.github;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class RestGitHubUserAuthorizationClient implements GitHubUserAuthorizationClient {
    private static final int PAGE_SIZE = 100;

    private final RestClient oauthClient;
    private final RestClient apiClient;
    private final ObjectMapper objectMapper;
    private final GitHubConnectionProperties properties;

    RestGitHubUserAuthorizationClient(
            RestClient oauthClient,
            RestClient apiClient,
            ObjectMapper objectMapper,
            GitHubConnectionProperties properties) {
        this.oauthClient = oauthClient;
        this.apiClient = apiClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public GitHubConnectionProof verify(String authorizationCode, String pkceVerifier) {
        if (!properties.configured()) {
            throw new GitHubConnectionException(GitHubConnectionError.NOT_CONFIGURED);
        }
        SecretToken token = exchange(authorizationCode, pkceVerifier);
        long userId = authenticatedUser(token);
        return new GitHubConnectionProof(userId, installations(token));
    }

    private SecretToken exchange(String code, String verifier) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("code", code);
        form.add("redirect_uri", properties.callbackUrl().toString());
        form.add("code_verifier", verifier);
        String body = read(oauthClient.post().uri("/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form), properties.maxResponseBytes());
        JsonNode root = parse(body);
        String value = text(root, "access_token", 4096);
        if (value == null) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_AUTHENTICATION_REJECTED);
        }
        return new SecretToken(value);
    }

    private long authenticatedUser(SecretToken token) {
        JsonNode root = parse(read(apiClient.get().uri("/user")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()), properties.maxResponseBytes()));
        long id = positiveLong(root.get("id"));
        if (id <= 0) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
        }
        return id;
    }

    private List<GitHubConnectionProof.AccessibleInstallation> installations(SecretToken token) {
        List<GitHubConnectionProof.AccessibleInstallation> result = new ArrayList<>();
        boolean hasNext = true;
        for (int page = 1; page <= properties.maxPages() && hasNext; page++) {
            int requestedPage = page;
            Page response = readPage(apiClient.get()
                    .uri(builder -> builder.path("/user/installations")
                            .queryParam("per_page", PAGE_SIZE).queryParam("page", requestedPage).build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token.value()));
            JsonNode root = parse(response.body());
            JsonNode entries = root.get("installations");
            if (entries == null || !entries.isArray()) {
                throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
            }
            for (JsonNode entry : entries) {
                JsonNode account = entry.get("account");
                long installationId = positiveLong(entry.get("id"));
                long accountId = account == null ? -1 : positiveLong(account.get("id"));
                String accountType = account == null ? null : text(account, "type", 32);
                String targetType = text(entry, "target_type", 32);
                if (installationId <= 0 || accountId <= 0 || accountType == null || targetType == null) {
                    throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
                }
                if (result.size() >= properties.maxInstallations()) {
                    throw new GitHubConnectionException(GitHubConnectionError.TOO_MANY_INSTALLATIONS);
                }
                result.add(new GitHubConnectionProof.AccessibleInstallation(
                        installationId, accountId, accountType, targetType));
            }
            hasNext = response.hasNext();
            if (hasNext && page == properties.maxPages()) {
                throw new GitHubConnectionException(GitHubConnectionError.TOO_MANY_INSTALLATIONS);
            }
        }
        return List.copyOf(result);
    }

    private Page readPage(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.exchange((httpRequest, response) -> {
                ensureSuccess(response.getStatusCode().value(), response.getHeaders());
                return new Page(readBounded(response), hasNext(response.getHeaders().getFirst("Link")));
            });
        } catch (GitHubConnectionException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_UNAVAILABLE);
        }
    }

    private String read(RestClient.RequestHeadersSpec<?> request, int ignoredLimit) {
        return readPage(request).body();
    }

    private String readBounded(org.springframework.http.client.ClientHttpResponse response) throws IOException {
        byte[] bytes = response.getBody().readNBytes(properties.maxResponseBytes() + 1);
        if (bytes.length > properties.maxResponseBytes()) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void ensureSuccess(int status, HttpHeaders headers) {
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == 429 || (status == 403 && ("0".equals(headers.getFirst("X-RateLimit-Remaining"))
                || headers.getFirst("Retry-After") != null))) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RATE_LIMITED);
        }
        if (status == 401 || status == 403 || status == 404) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_AUTHENTICATION_REJECTED);
        }
        throw new GitHubConnectionException(status >= 500
                ? GitHubConnectionError.GITHUB_UNAVAILABLE : GitHubConnectionError.GITHUB_RESPONSE_INVALID);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
            }
            return root;
        } catch (GitHubConnectionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new GitHubConnectionException(GitHubConnectionError.GITHUB_RESPONSE_INVALID);
        }
    }

    private static String text(JsonNode parent, String field, int maxLength) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value.isBlank() || value.length() > maxLength ? null : value;
    }

    private static long positiveLong(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToLong() ? node.asLong() : -1;
    }

    private static boolean hasNext(String link) {
        return link != null && java.util.Arrays.stream(link.split(","))
                .anyMatch(part -> part.matches("\\s*<[^>]+>\\s*;.*\\brel=\\\"next\\\".*"));
    }

    private record Page(String body, boolean hasNext) { }

    private static final class SecretToken {
        private final String value;
        private SecretToken(String value) { this.value = value; }
        private String value() { return value; }
        @Override public String toString() { return "SecretToken[<redacted>]"; }
    }
}
