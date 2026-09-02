package io.prreviewassistant.github.auth;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties("github.app")
@Validated
public record GitHubAppProperties(
        @NotBlank String id,
        @NotNull Path privateKeyPath,
        @NotNull URI apiBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration requestTimeout,
        @NotNull Duration tokenRefreshSkew) {

    public GitHubAppProperties {
        if (privateKeyPath != null && privateKeyPath.toString().isBlank()) {
            throw GitHubException.invalidConfiguration();
        }
        if (apiBaseUrl != null && (!apiBaseUrl.isAbsolute()
                || !("https".equalsIgnoreCase(apiBaseUrl.getScheme())
                || "http".equalsIgnoreCase(apiBaseUrl.getScheme()))
                || apiBaseUrl.getHost() == null
                || apiBaseUrl.getUserInfo() != null
                || apiBaseUrl.getQuery() != null
                || apiBaseUrl.getFragment() != null)) {
            throw GitHubException.invalidConfiguration();
        }
        if (isNotPositive(connectTimeout) || isNotPositive(requestTimeout) || isNotPositive(tokenRefreshSkew)) {
            throw GitHubException.invalidConfiguration();
        }
    }

    private static boolean isNotPositive(Duration duration) {
        return duration != null && (duration.isZero() || duration.isNegative());
    }
}
