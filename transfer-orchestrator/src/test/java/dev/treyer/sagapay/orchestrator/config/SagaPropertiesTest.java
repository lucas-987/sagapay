package dev.treyer.sagapay.orchestrator.config;

import dev.treyer.sagapay.orchestrator.application.service.SagaSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SagaPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withSystemProperties("SAGAPAY_FRAUD_VERIFICATION_CODE=code")
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties({
        SagaProperties.class,
        OutboxProperties.class,
        LedgerProperties.class,
        KafkaRetryProperties.class
    })
    static class Binding {}

    @Test
    void theDefaultsAreLoadedFromApplicationProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SagaProperties.class).toSettings())
                    .isEqualTo(new SagaSettings(5000, 10000, 120000, 5000, 100, 100));
            assertThat(context.getBean(OutboxProperties.class)).isEqualTo(new OutboxProperties(500, 100));
            assertThat(context.getBean(KafkaRetryProperties.class)).isEqualTo(new KafkaRetryProperties(3, 1000));
            assertThat(context.getBean(LedgerProperties.class).grpc().deadlineMs())
                    .isEqualTo(500);
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "saga.reprise.grace-period-ms=-1",
                "saga.reprise.sweep-interval-ms=0",
                "saga.reprise.batch-size=0",
                "saga.deadline.sweep-interval-ms=0",
                "saga.deadline.batch-size=0",
                "saga.screening.timeout-ms=0",
                "saga.confirmation.window-ms=0",
                "saga.confirmation.reservation-margin-ms=-1",
                "outbox.poll-interval-ms=0",
                "outbox.batch-size=0",
                "ledger.grpc.deadline-ms=0",
                "kafka.retry.attempts=-1",
                "kafka.retry.interval-ms=-1"
            })
    void aValueOutOfBoundsStopsTheStart(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aZeroGracePeriodIsAllowed() {
        runner.withPropertyValues("saga.reprise.grace-period-ms=0")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
