package io.prreviewassistant.github.auth;

public final class GitHubException extends RuntimeException {

    private final GitHubErrorType type;

    private GitHubException(GitHubErrorType type, String message) {
        super(message);
        this.type = type;
    }

    public GitHubErrorType type() {
        return type;
    }

    public static GitHubException invalidConfiguration() {
        return new GitHubException(
                GitHubErrorType.INVALID_CONFIGURATION,
                "GitHub App authentication configuration is invalid.");
    }

    public static GitHubException invalidInstallationId() {
        return new GitHubException(
                GitHubErrorType.INVALID_INSTALLATION_ID,
                "GitHub installation ID must be positive.");
    }

    public static GitHubException jwtGenerationFailed() {
        return new GitHubException(
                GitHubErrorType.JWT_GENERATION_FAILED,
                "GitHub App authentication JWT generation failed.");
    }

    public static GitHubException authenticationRejected() {
        return new GitHubException(
                GitHubErrorType.AUTHENTICATION_REJECTED,
                "GitHub rejected the authentication request.");
    }

    public static GitHubException installationNotFound() {
        return new GitHubException(
                GitHubErrorType.INSTALLATION_NOT_FOUND,
                "The GitHub App installation was not found or is not accessible.");
    }

    public static GitHubException rateLimited() {
        return new GitHubException(
                GitHubErrorType.RATE_LIMITED,
                "GitHub rate limited the request.");
    }

    public static GitHubException transientFailure() {
        return new GitHubException(
                GitHubErrorType.TRANSIENT_FAILURE,
                "GitHub is temporarily unavailable.");
    }

    public static GitHubException malformedResponse() {
        return new GitHubException(
                GitHubErrorType.MALFORMED_RESPONSE,
                "GitHub returned an invalid response.");
    }
}
