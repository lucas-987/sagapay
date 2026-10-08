package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.domain.FraudScorer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FraudRulesProperties.class)
class FraudRulesConfig {

    @Bean
    FraudScorer fraudScorer(FraudRulesProperties properties) {
        return new FraudScorer(properties.toSettings());
    }
}
