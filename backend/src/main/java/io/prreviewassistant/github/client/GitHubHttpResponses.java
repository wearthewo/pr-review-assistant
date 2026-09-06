package io.prreviewassistant.github.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.prreviewassistant.github.auth.GitHubException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class GitHubHttpResponses {

    static final int DEFAULT_MAX_RESPONSE_BYTES = 64 * 1024;

    private GitHubHttpResponses() {
    }

    static String read(RestClient.RequestHeadersSpec<?> request, boolean installationTokenRequest) {
        try {
            return read(request, installationTokenRequest, DEFAULT_MAX_RESPONSE_BYTES).body();
        } catch (GitHubException exception) {
            if (exception.type() == io.prreviewassistant.github.auth.GitHubErrorType.RESPONSE_TOO_LARGE) {
                throw GitHubException.malformedResponse();
            }
            throw exception;
        }
    }

    static GitHubHttpResponse read(
            RestClient.RequestHeadersSpec<?> request,
            boolean installationTokenRequest,
            int maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        try {
            return request.exchange((httpRequest, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw GitHubResponseErrors.map(
                            response.getStatusCode(), response.getHeaders(), installationTokenRequest);
                }
                return new GitHubHttpResponse(
                        readBoundedBody(response, maxResponseBytes),
                        hasNextPage(response.getHeaders().getFirst("Link")));
            });
        } catch (GitHubException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw GitHubException.transientFailure();
        }
    }

    private static String readBoundedBody(
            org.springframework.http.client.ClientHttpResponse response,
            int maxResponseBytes)
            throws IOException {
        byte[] bytes = response.getBody().readNBytes(maxResponseBytes + 1);
        if (bytes.length > maxResponseBytes) {
            throw GitHubException.responseTooLarge();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static boolean hasNextPage(String linkHeader) {
        if (linkHeader == null) {
            return false;
        }
        for (String link : linkHeader.split(",")) {
            if (link.matches("\\s*<[^>]+>\\s*;.*\\brel=\\\"next\\\".*")) {
                return true;
            }
        }
        return false;
    }
}
