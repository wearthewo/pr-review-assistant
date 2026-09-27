package io.prreviewassistant.deployment;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import io.prreviewassistant.dashboard.auth.DashboardAuthProperties;
import io.prreviewassistant.dashboard.github.GitHubConnectionProperties;
import io.prreviewassistant.github.auth.GitHubAppProperties;
import io.prreviewassistant.github.auth.PemPrivateKeyLoader;
import org.junit.jupiter.api.Test;

class ProductionConfigurationValidatorTest {

    @Test
    void failsClosedWhenDashboardAuthenticationIsMissing() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                new DashboardAuthProperties("", "", ""),
                configuredGitHubConnection(), githubApp(Path.of("sensitive-key-name.pem")),
                mock(PemPrivateKeyLoader.class));

        assertThatIllegalStateException().isThrownBy(validator::afterSingletonsInstantiated)
                .withMessage("production dashboard authentication is not configured")
                .withMessageNotContaining("sensitive-key-name");
    }

    @Test
    void failsClosedWithoutExposingPrivateKeyPathWhenKeyCannotBeLoaded() {
        ProductionConfigurationValidator validator = new ProductionConfigurationValidator(
                new DashboardAuthProperties(
                        "https://tenant.auth0.com/", "https://api.example.com",
                        "https://tenant.auth0.com/.well-known/jwks.json"),
                configuredGitHubConnection(), githubApp(Path.of("sensitive-key-name.pem")),
                new PemPrivateKeyLoader());

        assertThatIllegalStateException().isThrownBy(validator::afterSingletonsInstantiated)
                .withMessage("production GitHub App private key is invalid")
                .withMessageNotContaining("sensitive-key-name");
    }

    private static GitHubConnectionProperties configuredGitHubConnection() {
        return new GitHubConnectionProperties("1",
                "client-id", "client-secret", URI.create("https://app.example.com/github/callback"),
                URI.create("https://github.com"), Duration.ofMinutes(10), 5, 10, 1000, 262144);
    }

    private static GitHubAppProperties githubApp(Path path) {
        return new GitHubAppProperties(
                "123", path, URI.create("https://api.github.com"),
                Duration.ofSeconds(5), Duration.ofSeconds(20), Duration.ofMinutes(5));
    }
}
