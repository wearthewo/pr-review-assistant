package io.prreviewassistant.github.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class GitHubAppPropertiesTest {

    @Test
    void rejectsApiBaseUrlContainingCredentials() {
        assertThatThrownBy(() -> properties(URI.create("https://user:secret@api.github.test"), Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(GitHubException.class,
                        exception -> assertThat(exception.type())
                                .isEqualTo(GitHubErrorType.INVALID_CONFIGURATION))
                .extracting(Throwable::toString)
                .asString()
                .doesNotContain("user", "secret");
    }

    @Test
    void rejectsNonPositiveTimeout() {
        assertThatThrownBy(() -> properties(URI.create("https://api.github.test"), Duration.ZERO))
                .isInstanceOfSatisfying(GitHubException.class,
                        exception -> assertThat(exception.type())
                                .isEqualTo(GitHubErrorType.INVALID_CONFIGURATION));
    }

    private GitHubAppProperties properties(URI apiBaseUrl, Duration connectTimeout) {
        return new GitHubAppProperties(
                "test-app-id",
                Path.of("unused.pem"),
                apiBaseUrl,
                connectTimeout,
                Duration.ofSeconds(20),
                Duration.ofMinutes(5));
    }
}
