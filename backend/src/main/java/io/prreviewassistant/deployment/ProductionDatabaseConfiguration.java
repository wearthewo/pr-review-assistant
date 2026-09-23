package io.prreviewassistant.deployment;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("production")
@EnableConfigurationProperties(RenderPostgresProperties.class)
class ProductionDatabaseConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    DataSource productionDataSource(RenderPostgresProperties renderProperties) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(renderProperties.jdbcUrl());
        properties.setUsername(renderProperties.username());
        properties.setPassword(renderProperties.password());
        properties.setDriverClassName("org.postgresql.Driver");
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
