package io.prreviewassistant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class PostgreSqlTestConfiguration {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.6-bookworm");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresqlContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
