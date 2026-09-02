package io.prreviewassistant.github.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.prreviewassistant.github.auth.GitHubException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

final class GitHubHttpResponses {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private GitHubHttpResponses() {
    }

    static String read(RestClient.RequestHeadersSpec<?> request, boolean installationTokenRequest) {
        try {
            return request.exchange((httpRequest, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) {
                    throw GitHubResponseErrors.map(
                            response.getStatusCode(), response.getHeaders(), installationTokenRequest);
                }
                return readBoundedBody(response);
            });
        } catch (GitHubException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw GitHubException.transientFailure();
        }
    }

    private static String readBoundedBody(org.springframework.http.client.ClientHttpResponse response)
            throws IOException {
        byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) {
            throw GitHubException.malformedResponse();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
