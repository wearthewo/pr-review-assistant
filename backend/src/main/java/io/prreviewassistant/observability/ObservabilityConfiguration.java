package io.prreviewassistant.observability;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    @Bean
    ApplicationMetrics applicationMetrics(MeterRegistry registry) {
        return new ApplicationMetrics(registry);
    }

    @Bean
    QueueMetrics queueMetrics(MeterRegistry registry, JdbcClient jdbcClient, Clock clock) {
        return new QueueMetrics(registry, jdbcClient, clock);
    }
}
