package io.prreviewassistant.dashboard.github;

public final class GitHubConnectionException extends RuntimeException {
    private final GitHubConnectionError error;

    public GitHubConnectionException(GitHubConnectionError error) {
        super("GitHub connection could not be completed");
        this.error = error;
    }

    public GitHubConnectionError error() {
        return error;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{error=" + error + '}';
    }
}
