package dev.treyer.sagapay.ledger.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties(ReservationProperties.class)
    static class Binding {}

    @Test
    void theMinimumIsAccepted() {
        runner.withPropertyValues("ledger.reservation.ttl-ms=60000")
                .run(context -> assertThat(context.getBean(ReservationProperties.class)
                                .toSettings()
                                .ttlMs())
                        .isEqualTo(60000));
    }

    @ParameterizedTest
    @ValueSource(longs = {59999, 0, -1})
    void aTimeTooShortToOutlastTheScreeningStopsTheStartup(long ttlMs) {
        runner.withPropertyValues("ledger.reservation.ttl-ms=" + ttlMs)
                .run(context -> assertThat(context).hasFailed());
    }
}
