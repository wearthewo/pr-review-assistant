package io.prreviewassistant.dashboard.github;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class GitHubConnectionPropertiesTest {

    @Test
    void activeStateLimitMustRemainWithinSecurityBounds() {
        assertThatThrownBy(() -> properties(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(21)).isInstanceOf(IllegalArgumentException.class);
    }

    private static GitHubConnectionProperties properties(int activeStates) {
        return new GitHubConnectionProperties("1", "client", "secret",
                URI.create("https://app.example/github/callback"), URI.create("https://github.com"),
                Duration.ofMinutes(10), activeStates, 10, 1000, 262144);
    }
}
