package io.prreviewassistant.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class ProductionDatabaseConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> {
                try {
                    var sources = new YamlPropertySourceLoader()
                            .load("production", new ClassPathResource("application-production.yml"));
                    sources.forEach(context.getEnvironment().getPropertySources()::addLast);
                } catch (IOException exception) {
                    throw new IllegalStateException("could not load production configuration", exception);
                }
            })
            .withPropertyValues(
                    "spring.profiles.active=production",
                    "deployment.database.connection-uri=postgresql://runtime_user:secret@db.internal:5432/runtime_db",
                    "deployment.database.username=runtime_user",
                    "deployment.database.password=secret",
                    "deployment.database.database=runtime_db")
            .withUserConfiguration(ProductionDatabaseConfiguration.class);

    @Test
    void productionProfileBindsRenderDatabaseAndBoundedPoolSettings() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            HikariDataSource dataSource = context.getBean(HikariDataSource.class);
            assertThat(dataSource.getJdbcUrl())
                    .isEqualTo("jdbc:postgresql://db.internal:5432/runtime_db?sslmode=require");
            assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
            assertThat(dataSource.getMinimumIdle()).isEqualTo(1);
            assertThat(dataSource.getConnectionTimeout()).isEqualTo(10_000L);
            assertThat(dataSource.getValidationTimeout()).isEqualTo(5_000L);
            assertThat(dataSource.getIdleTimeout()).isEqualTo(300_000L);
            assertThat(dataSource.getMaxLifetime()).isEqualTo(1_500_000L);
        });
    }
}
