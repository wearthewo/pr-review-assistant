package io.prreviewassistant.github.client;

public final class GitHubReviewException extends RuntimeException {
    private final GitHubReviewErrorType type;
    private GitHubReviewException(GitHubReviewErrorType type) {
        super("GitHub review publication failed: " + type.name().toLowerCase(java.util.Locale.ROOT) + ".");
        this.type=type;
    }
    public GitHubReviewErrorType type(){return type;}
    public static GitHubReviewException of(GitHubReviewErrorType type){return new GitHubReviewException(type);}
}
