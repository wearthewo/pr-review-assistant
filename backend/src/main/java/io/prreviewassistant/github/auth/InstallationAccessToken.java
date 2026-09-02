package io.prreviewassistant.github.auth;

import java.time.Instant;

public final class InstallationAccessToken {

    private final String value;
    private final Instant expiresAt;

    public InstallationAccessToken(String value, Instant expiresAt) {
        if (value == null || value.isBlank()) {
            throw GitHubException.malformedResponse();
        }
        this.value = value;
        if (expiresAt == null) {
            throw GitHubException.malformedResponse();
        }
        this.expiresAt = expiresAt;
    }

    public String value() {
        return value;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "InstallationAccessToken[expiresAt=" + expiresAt + ", token=REDACTED]";
    }
}
