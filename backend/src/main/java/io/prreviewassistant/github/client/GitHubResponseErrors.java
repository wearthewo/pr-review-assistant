package io.prreviewassistant.github.client;

import io.prreviewassistant.github.auth.GitHubException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

final class GitHubResponseErrors {

    private GitHubResponseErrors() {
    }

    static GitHubException map(HttpStatusCode status, HttpHeaders headers, boolean installationTokenRequest) {
        if (status.value() == 429
                || (status.value() == 403 && "0".equals(headers.getFirst("X-RateLimit-Remaining")))) {
            return GitHubException.rateLimited();
        }
        if (status.value() == 404 && installationTokenRequest) {
            return GitHubException.installationNotFound();
        }
        if (status.value() == 401 || status.value() == 403) {
            return GitHubException.authenticationRejected();
        }
        if (status.is5xxServerError()) {
            return GitHubException.transientFailure();
        }
        return GitHubException.authenticationRejected();
    }
}
