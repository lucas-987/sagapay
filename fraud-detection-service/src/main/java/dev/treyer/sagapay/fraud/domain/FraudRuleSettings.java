package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;

public record FraudRuleSettings(
        BigDecimal velocityWeight,
        int velocityMaxPerHour,
        BigDecimal newRecipientWeight,
        BigDecimal amountRoundWeight,
        BigDecimal amountRoundThreshold,
        BigDecimal flagThreshold,
        String modelVersion) {

    BigDecimal weightOf(FraudRule rule) {
        return switch (rule) {
            case VELOCITY_1H -> velocityWeight;
            case NEW_RECIPIENT -> newRecipientWeight;
            case AMOUNT_ROUND -> amountRoundWeight;
        };
    }
}
