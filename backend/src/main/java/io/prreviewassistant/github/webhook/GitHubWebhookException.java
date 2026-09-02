package io.prreviewassistant.github.webhook;

final class GitHubWebhookException extends RuntimeException {

    private final GitHubWebhookError error;

    private GitHubWebhookException(GitHubWebhookError error) {
        super(error.message());
        this.error = error;
    }

    static GitHubWebhookException invalidConfiguration() {
        return new GitHubWebhookException(GitHubWebhookError.INVALID_CONFIGURATION);
    }

    static GitHubWebhookException unauthorized() {
        return new GitHubWebhookException(GitHubWebhookError.UNAUTHORIZED);
    }

    static GitHubWebhookException invalidRequest() {
        return new GitHubWebhookException(GitHubWebhookError.INVALID_REQUEST);
    }

    static GitHubWebhookException payloadTooLarge() {
        return new GitHubWebhookException(GitHubWebhookError.PAYLOAD_TOO_LARGE);
    }

    GitHubWebhookError error() {
        return error;
    }
}
