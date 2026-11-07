package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.domain.FraudRuleSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRulesPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Binding.class);

    @EnableConfigurationProperties(FraudRulesProperties.class)
    static class Binding {}

    @Test
    void theDefaultsAreLoadedFromApplicationProperties() {
        runner.run(context -> {
            FraudRuleSettings settings =
                    context.getBean(FraudRulesProperties.class).toSettings();

            assertThat(settings.velocityWeight()).isEqualByComparingTo("0.40");
            assertThat(settings.velocityMaxInWindow()).isEqualTo(5);
            assertThat(settings.newRecipientWeight()).isEqualByComparingTo("0.35");
            assertThat(settings.amountRoundWeight()).isEqualByComparingTo("0.35");
            assertThat(settings.amountRoundThreshold()).isEqualByComparingTo("500");
            assertThat(settings.amountRoundStep()).isEqualByComparingTo("100");
            assertThat(settings.flagThreshold()).isEqualByComparingTo("0.70");
            assertThat(settings.modelVersion()).isEqualTo("rules-v1");
        });
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        runner.withSystemProperties(
                        "FRAUD_RULES_VELOCITY_WEIGHT=0.55",
                        "FRAUD_RULES_VELOCITY_MAX_IN_WINDOW=9",
                        "FRAUD_RULES_VELOCITY_WINDOW_SECONDS=600",
                        "FRAUD_RULES_NEW_RECIPIENT_WEIGHT=0.10",
                        "FRAUD_RULES_AMOUNT_ROUND_WEIGHT=0.20",
                        "FRAUD_RULES_AMOUNT_ROUND_THRESHOLD=1000",
                        "FRAUD_RULES_AMOUNT_ROUND_STEP=50",
                        "FRAUD_RULES_FLAG_THRESHOLD=0.50",
                        "FRAUD_RULES_MODEL_VERSION=rules-v2")
                .run(context -> {
                    FraudRuleSettings settings =
                            context.getBean(FraudRulesProperties.class).toSettings();

                    assertThat(settings.velocityWeight()).isEqualByComparingTo("0.55");
                    assertThat(settings.velocityMaxInWindow()).isEqualTo(9);
                    assertThat(context.getBean(FraudRulesProperties.class)
                                    .velocity()
                                    .windowSeconds())
                            .isEqualTo(600);
                    assertThat(settings.newRecipientWeight()).isEqualByComparingTo("0.10");
                    assertThat(settings.amountRoundWeight()).isEqualByComparingTo("0.20");
                    assertThat(settings.amountRoundThreshold()).isEqualByComparingTo("1000");
                    assertThat(settings.amountRoundStep()).isEqualByComparingTo("50");
                    assertThat(settings.flagThreshold()).isEqualByComparingTo("0.50");
                    assertThat(settings.modelVersion()).isEqualTo("rules-v2");
                });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "fraud.rules.velocity.weight=-0.01",
                "fraud.rules.velocity.max-in-window=-1",
                "fraud.rules.velocity.window-seconds=0",
                "fraud.rules.new-recipient.weight=-0.01",
                "fraud.rules.new-recipient.memory-seconds=0",
                "fraud.rules.amount-round.weight=-0.01",
                "fraud.rules.amount-round.threshold=-1",
                "fraud.rules.amount-round.step=0",
                "fraud.rules.flag-threshold=-0.01",
                "fraud.rules.flag-threshold=1.01",
                "fraud.rules.model-version="
            })
    void aValueOutOfBoundsStopsTheStart(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"fraud.rules.flag-threshold=0", "fraud.rules.flag-threshold=1"})
    void theBoundsOfTheFlagThresholdAreAccepted(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasNotFailed());
    }
}
