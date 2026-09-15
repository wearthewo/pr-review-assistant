package io.prreviewassistant.dashboard.auth;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("dashboard.auth")
public record DashboardAuthProperties(String issuer, String audience, String jwkSetUri) {
    private static final int MAX_URI_LENGTH = 2048;
    private static final int MAX_AUDIENCE_LENGTH = 512;

    public DashboardAuthProperties {
        issuer = normalize(issuer);
        audience = normalize(audience);
        jwkSetUri = normalize(jwkSetUri);
        boolean any = !issuer.isEmpty() || !audience.isEmpty() || !jwkSetUri.isEmpty();
        boolean all = !issuer.isEmpty() && !audience.isEmpty() && !jwkSetUri.isEmpty();
        if (any && !all) {
            throw new IllegalArgumentException("dashboard authentication configuration is incomplete");
        }
        if (all) {
            validateIssuerAndJwkSet(issuer, jwkSetUri);
            validateAudience(audience);
        }
    }

    public boolean configured() {
        return !issuer.isEmpty();
    }

    private static void validateIssuerAndJwkSet(String issuerValue, String jwkSetValue) {
        if (issuerValue.length() > MAX_URI_LENGTH || jwkSetValue.length() > MAX_URI_LENGTH) {
            throw new IllegalArgumentException("dashboard authentication URI is invalid");
        }
        URI issuerUri = parseHttpsOrLoopback(issuerValue);
        URI jwkSet = parseHttpsOrLoopback(jwkSetValue);
        if (!sameOrigin(issuerUri, jwkSet)
                || issuerUri.getRawQuery() != null || issuerUri.getRawFragment() != null
                || jwkSet.getRawQuery() != null || jwkSet.getRawFragment() != null) {
            throw new IllegalArgumentException("dashboard authentication URI is invalid");
        }
    }

    private static URI parseHttpsOrLoopback(String value) {
        try {
            URI uri = URI.create(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopbackHttp = "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "localhost".equalsIgnoreCase(uri.getHost()));
            if ((!https && !loopbackHttp) || uri.getHost() == null || uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("dashboard authentication URI is invalid");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("dashboard authentication URI is invalid");
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void validateAudience(String value) {
        if (value.length() > MAX_AUDIENCE_LENGTH || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("dashboard authentication audience is invalid");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public String toString() {
        return "DashboardAuthProperties[configured=" + configured() + ", values=<redacted>]";
    }
}
