package dev.treyer.sagapay.fraud.config;

import dev.treyer.sagapay.fraud.domain.FraudRuleSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fraud.rules")
public record FraudRulesProperties(
        Velocity velocity1h,
        NewRecipient newRecipient,
        AmountRound amountRound,
        BigDecimal flagThreshold,
        String modelVersion) {

    public record Velocity(BigDecimal weight, int maxPerHour) {}

    public record NewRecipient(BigDecimal weight) {}

    public record AmountRound(BigDecimal weight, BigDecimal threshold) {}

    public FraudRuleSettings toSettings() {
        return new FraudRuleSettings(
                velocity1h.weight(),
                velocity1h.maxPerHour(),
                newRecipient.weight(),
                amountRound.weight(),
                amountRound.threshold(),
                flagThreshold,
                modelVersion);
    }
}
