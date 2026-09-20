package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://invalid:5432/invalid",
        "DB_USERNAME=test",
        "DB_PASSWORD=test",
        "GITHUB_APP_ID=test-app-id",
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem",
        "GITHUB_WEBHOOK_SECRET=test-webhook-secret-with-entropy"
})
@Import(PostgreSqlTestConfiguration.class)
class PostgreSqlIntegrationTest {

    @Autowired
    private PostgreSQLContainer postgresqlContainer;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private Environment environment;

    @Test
    void startsWithPostgreSqlFlywayAndSchemaValidation() throws SQLException {
        assertThat(postgresqlContainer.isRunning()).isTrue();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:postgresql:");
            assertThat(connection.isValid(1)).isTrue();
        }

        assertThat(flyway.info().pending()).isEmpty();
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void v6PreservesPopulatedV5HistoryWithoutInventingOwnershipOrUsage() throws SQLException {
        String schema = "m15_history_" + UUID.randomUUID().toString().replace("-", "");
        Flyway throughV5 = Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("5"))
                .load();
        throughV5.migrate();
        UUID jobId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 13, 12, 0, 0, 0, ZoneOffset.UTC);

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(), postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var job = connection.prepareStatement("""
                    INSERT INTO review_jobs
                      (id, status, attempts, max_attempts, next_attempt_at, created_at, updated_at)
                    VALUES (?, 'READY', 0, 3, ?, ?, ?)
                    """)) {
                job.setObject(1, jobId);
                job.setObject(2, now);
                job.setObject(3, now);
                job.setObject(4, now);
                job.executeUpdate();
            }
            try (var publication = connection.prepareStatement("""
                    INSERT INTO review_publications
                      (id, analysis_job_id, github_installation_id, github_repository_id,
                       github_repository_owner, github_repository_name, github_pull_request_number,
                       github_head_sha, publication_key, payload_version, finding_count, payload,
                       status, created_at, updated_at)
                    VALUES (?, ?, 1, 2, 'owner', 'repo', 3, ?, ?, 1, 1, '{}', 'PENDING', ?, ?)
                    """)) {
                publication.setObject(1, publicationId);
                publication.setObject(2, jobId);
                publication.setString(3, "a".repeat(40));
                publication.setString(4, "b".repeat(64));
                publication.setObject(5, now);
                publication.setObject(6, now);
                publication.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(), postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                            SELECT job.tenant_id AS job_tenant,
                                   publication.tenant_id AS publication_tenant
                            FROM review_jobs job
                            JOIN review_publications publication ON publication.analysis_job_id = job.id
                            """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getObject("job_tenant")).isNull();
                assertThat(rows.getObject("publication_tenant")).isNull();
                try (var usage = connection.createStatement()
                        .executeQuery("SELECT count(*) AS usage_count FROM tenant_usage_events")) {
                    assertThat(usage.next()).isTrue();
                    assertThat(usage.getLong("usage_count")).isZero();
                }
            } finally {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v7PreservesPopulatedV6TenantWithoutInventingUsersOrMemberships() throws SQLException {
        String schema = "m13b_history_" + UUID.randomUUID().toString().replace("-", "");
        Flyway throughV6 = Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("6"))
                .load();
        throughV6.migrate();
        UUID tenantId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 15, 12, 0, 0, 0, ZoneOffset.UTC);

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var tenant = connection.prepareStatement(
                    "INSERT INTO tenants (id, created_at, updated_at) VALUES (?, ?, ?)")) {
                tenant.setObject(1, tenantId);
                tenant.setObject(2, now);
                tenant.setObject(3, now);
                tenant.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                            SELECT (SELECT count(*) FROM tenants) AS tenants,
                                   (SELECT count(*) FROM application_users) AS users,
                                   (SELECT count(*) FROM tenant_memberships) AS memberships
                            """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("tenants")).isEqualTo(1);
                assertThat(rows.getLong("users")).isZero();
                assertThat(rows.getLong("memberships")).isZero();
            } finally {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v9PreservesPopulatedV8ConnectionStateAndAddsPruningIndex() throws SQLException {
        String schema = "m17_history_" + UUID.randomUUID().toString().replace("-", "");
        Flyway throughV8 = Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("8"))
                .load();
        throughV8.migrate();
        UUID userId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.of(2026, 9, 20, 12, 0, 0, 0, ZoneOffset.UTC);

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var user = connection.prepareStatement("""
                    INSERT INTO application_users (id, auth_issuer, auth_subject, created_at, updated_at)
                    VALUES (?, 'https://issuer.example/', 'm17-user', ?, ?)
                    """);
                    var state = connection.prepareStatement("""
                    INSERT INTO github_connection_states
                      (state_hash, application_user_id, pkce_verifier, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                user.setObject(1, userId);
                user.setObject(2, now);
                user.setObject(3, now);
                user.executeUpdate();
                state.setString(1, "a".repeat(64));
                state.setObject(2, userId);
                state.setString(3, "v".repeat(43));
                state.setObject(4, now.plusMinutes(10));
                state.setObject(5, now);
                state.executeUpdate();
            }
        }

        Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .load()
                .migrate();

        try (Connection connection = DriverManager.getConnection(
                postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                postgresqlContainer.getPassword())) {
            connection.setSchema(schema);
            try (var statement = connection.createStatement();
                    var rows = statement.executeQuery("""
                            SELECT
                              (SELECT count(*) FROM github_connection_states) AS states,
                              (SELECT count(*) FROM pg_indexes
                               WHERE schemaname = current_schema()
                                 AND indexname = 'ix_github_connection_states_active_user') AS indexes
                            """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("states")).isEqualTo(1);
                assertThat(rows.getLong("indexes")).isEqualTo(1);
            } finally {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
