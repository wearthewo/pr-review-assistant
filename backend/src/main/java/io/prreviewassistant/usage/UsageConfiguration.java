package io.prreviewassistant.usage;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UsageProperties.class)
class UsageConfiguration {
    @Bean
    QuotaPolicy quotaPolicy(UsageProperties properties) {
        return new QuotaPolicy(properties.monthlyLimit());
    }

    @Bean
    UsageAccountingService usageAccountingService(
            UsageAccountingStore store, QuotaPolicy quotaPolicy, Clock clock) {
        return new UsageAccountingService(store, quotaPolicy, clock);
    }
}
