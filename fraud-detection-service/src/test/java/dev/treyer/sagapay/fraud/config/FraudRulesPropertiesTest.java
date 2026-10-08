package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.domain.FraudRuleSettings;
import org.junit.jupiter.api.Test;
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
            assertThat(settings.velocityMaxPerHour()).isEqualTo(5);
            assertThat(settings.newRecipientWeight()).isEqualByComparingTo("0.35");
            assertThat(settings.amountRoundWeight()).isEqualByComparingTo("0.35");
            assertThat(settings.amountRoundThreshold()).isEqualByComparingTo("500");
            assertThat(settings.flagThreshold()).isEqualByComparingTo("0.70");
            assertThat(settings.modelVersion()).isEqualTo("rules-v1");
        });
    }

    @Test
    void environmentVariablesOverrideTheDefaults() {
        runner.withSystemProperties(
                        "FRAUD_RULES_VELOCITY_1H_WEIGHT=0.55",
                        "FRAUD_RULES_VELOCITY_1H_MAX_PER_HOUR=9",
                        "FRAUD_RULES_NEW_RECIPIENT_WEIGHT=0.10",
                        "FRAUD_RULES_AMOUNT_ROUND_WEIGHT=0.20",
                        "FRAUD_RULES_AMOUNT_ROUND_THRESHOLD=1000",
                        "FRAUD_RULES_FLAG_THRESHOLD=0.50",
                        "FRAUD_RULES_MODEL_VERSION=rules-v2")
                .run(context -> {
                    FraudRuleSettings settings =
                            context.getBean(FraudRulesProperties.class).toSettings();

                    assertThat(settings.velocityWeight()).isEqualByComparingTo("0.55");
                    assertThat(settings.velocityMaxPerHour()).isEqualTo(9);
                    assertThat(settings.newRecipientWeight()).isEqualByComparingTo("0.10");
                    assertThat(settings.amountRoundWeight()).isEqualByComparingTo("0.20");
                    assertThat(settings.amountRoundThreshold()).isEqualByComparingTo("1000");
                    assertThat(settings.flagThreshold()).isEqualByComparingTo("0.50");
                    assertThat(settings.modelVersion()).isEqualTo("rules-v2");
                });
    }
}
