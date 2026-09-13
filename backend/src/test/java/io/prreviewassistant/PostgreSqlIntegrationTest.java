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
    void v5PreservesHistoricalReviewAndPublicationRowsWithoutInventingOwnership() throws SQLException {
        String schema = "m14_history_" + UUID.randomUUID().toString().replace("-", "");
        Flyway throughV4 = Flyway.configure()
                .dataSource(postgresqlContainer.getJdbcUrl(), postgresqlContainer.getUsername(),
                        postgresqlContainer.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .target(MigrationVersion.fromVersion("4"))
                .load();
        throughV4.migrate();
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
            } finally {
                connection.createStatement().execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
