package io.prreviewassistant.dashboard.github;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dashboard.github-connection")
public record GitHubConnectionProperties(
        String clientId,
        String clientSecret,
        URI callbackUrl,
        URI oauthBaseUrl,
        Duration stateTtl,
        int maxPages,
        int maxInstallations,
        int maxResponseBytes) {

    public GitHubConnectionProperties {
        clientId = normalize(clientId);
        clientSecret = normalize(clientSecret);
        if (oauthBaseUrl == null) {
            oauthBaseUrl = URI.create("https://github.com");
        }
        if (stateTtl == null) {
            stateTtl = Duration.ofMinutes(10);
        }
        validateUri(oauthBaseUrl, false);
        if (callbackUrl != null) {
            validateUri(callbackUrl, true);
        }
        if (stateTtl.isZero() || stateTtl.isNegative() || stateTtl.compareTo(Duration.ofMinutes(30)) > 0
                || maxPages < 1 || maxPages > 20 || maxInstallations < 1 || maxInstallations > 2000
                || maxResponseBytes < 1024 || maxResponseBytes > 1024 * 1024) {
            throw new IllegalArgumentException("GitHub connection configuration is invalid");
        }
    }

    public boolean configured() {
        return clientId != null && clientSecret != null && callbackUrl != null;
    }

    @Override
    public String toString() {
        return "GitHubConnectionProperties[configured=" + configured() + "]";
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void validateUri(URI value, boolean callback) {
        boolean local = "http".equalsIgnoreCase(value.getScheme())
                && ("localhost".equalsIgnoreCase(value.getHost()) || "127.0.0.1".equals(value.getHost()));
        if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                || value.getFragment() != null || (!"https".equalsIgnoreCase(value.getScheme()) && !local)
                || (!callback && (value.getQuery() != null || !isRootPath(value.getPath())))) {
            throw new IllegalArgumentException("GitHub connection URL configuration is invalid");
        }
    }

    private static boolean isRootPath(String path) {
        return path == null || path.isEmpty() || "/".equals(path);
    }
}
