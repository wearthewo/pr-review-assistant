package io.prreviewassistant.dashboard.github;

public interface GitHubUserAuthorizationClient {
    GitHubConnectionProof verify(String authorizationCode, String pkceVerifier);
}
