package io.prreviewassistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
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
        "GITHUB_PRIVATE_KEY_PATH=unused-test-key.pem"
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
}
