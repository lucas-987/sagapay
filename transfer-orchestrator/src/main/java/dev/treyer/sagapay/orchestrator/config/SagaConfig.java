package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.application.service.SagaSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    SagaProperties.class,
    OutboxProperties.class,
    LedgerProperties.class,
    KafkaRetryProperties.class
})
class SagaConfig {

    @Bean
    SagaSettings sagaSettings(SagaProperties properties) {
        return properties.toSettings();
    }
}
