package io.prreviewassistant.deployment;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("deployment.database")
public record RenderPostgresProperties(
        String connectionUri,
        String username,
        String password,
        String database) {

    private static final int MAX_CONNECTION_URI_LENGTH = 4096;
    private static final int MAX_CREDENTIAL_LENGTH = 1024;

    public RenderPostgresProperties {
        connectionUri = requireBounded(connectionUri, MAX_CONNECTION_URI_LENGTH);
        username = requireBounded(username, MAX_CREDENTIAL_LENGTH);
        password = requireBounded(password, MAX_CREDENTIAL_LENGTH);
        database = requireDatabaseName(database);
    }

    public String jdbcUrl() {
        URI uri = parseConnectionUri();
        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 5432 : uri.getPort();
        String expectedPath = "/" + database;
        if (!"postgresql".equalsIgnoreCase(uri.getScheme())
                || host == null || host.isBlank() || host.length() > 253
                || port < 1 || port > 65535
                || uri.getRawUserInfo() == null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !expectedPath.equals(uri.getPath())) {
            throw invalid();
        }
        String jdbcHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return "jdbc:postgresql://" + jdbcHost + ":" + port + expectedPath + "?sslmode=require";
    }

    private URI parseConnectionUri() {
        try {
            return new URI(connectionUri);
        } catch (URISyntaxException exception) {
            throw invalid();
        }
    }

    private static String requireDatabaseName(String value) {
        String normalized = requireBounded(value, 63);
        if (!normalized.matches("[A-Za-z0-9_]+")) {
            throw invalid();
        }
        return normalized;
    }

    private static String requireBounded(String value, int maximumLength) {
        if (value == null || value.isBlank() || value.length() > maximumLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("production database configuration is invalid");
    }

    @Override
    public String toString() {
        return "RenderPostgresProperties[values=<redacted>]";
    }
}
