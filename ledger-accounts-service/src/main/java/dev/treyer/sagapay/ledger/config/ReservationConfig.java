package dev.treyer.sagapay.ledger.config;

import dev.treyer.sagapay.ledger.application.service.ReservationSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
class ReservationConfig {

    @Bean
    ReservationSettings reservationSettings(ReservationProperties properties) {
        return properties.toSettings();
    }
}
