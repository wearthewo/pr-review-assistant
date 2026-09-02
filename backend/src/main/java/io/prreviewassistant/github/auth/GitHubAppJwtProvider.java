package io.prreviewassistant.github.auth;

@FunctionalInterface
public interface GitHubAppJwtProvider {

    GitHubAppJwt createJwt();
}
