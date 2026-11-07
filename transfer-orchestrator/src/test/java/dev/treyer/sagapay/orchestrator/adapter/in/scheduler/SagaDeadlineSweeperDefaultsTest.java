package dev.treyer.sagapay.orchestrator.adapter.in.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SagaDeadlineSweeperDefaultsTest {

    @Test
    void theSweepIntervalDefaultsToOneSecond() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertThat(context.getEnvironment().getProperty("saga.deadline.sweep-interval-ms"))
                        .isEqualTo("1000"));
    }
}
