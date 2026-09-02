package io.prreviewassistant.github.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class InstallationAccessTokenTest {

    @Test
    void tokenSecretDoesNotAppearInToString() {
        String token = "opaque-installation-token-secret";

        assertThat(new InstallationAccessToken(token, Instant.parse("2026-09-02T11:00:00Z")).toString())
                .doesNotContain(token)
                .contains("REDACTED");
    }
}
