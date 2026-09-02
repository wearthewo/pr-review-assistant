package io.prreviewassistant.github.auth;

import java.util.Objects;

public final class GitHubAppJwt {

    private final String value;

    public GitHubAppJwt(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "GitHubAppJwt[REDACTED]";
    }
}
