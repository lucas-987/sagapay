package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;

public record FraudRuleSettings(
        BigDecimal velocityWeight,
        int velocityMaxInWindow,
        BigDecimal newRecipientWeight,
        BigDecimal amountRoundWeight,
        BigDecimal amountRoundThreshold,
        BigDecimal amountRoundStep,
        BigDecimal flagThreshold,
        String modelVersion) {

    BigDecimal weightOf(FraudRule rule) {
        return switch (rule) {
            case VELOCITY -> velocityWeight;
            case NEW_RECIPIENT -> newRecipientWeight;
            case AMOUNT_ROUND -> amountRoundWeight;
        };
    }
}
