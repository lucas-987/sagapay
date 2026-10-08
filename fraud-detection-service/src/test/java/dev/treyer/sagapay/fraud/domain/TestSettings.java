package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;

final class TestSettings {

    private TestSettings() {}

    static FraudRuleSettings defaults() {
        return withAmountRoundThreshold(new BigDecimal("500"));
    }

    static FraudRuleSettings withAmountRoundThreshold(BigDecimal threshold) {
        return new FraudRuleSettings(
                new BigDecimal("0.40"),
                5,
                new BigDecimal("0.35"),
                new BigDecimal("0.35"),
                threshold,
                new BigDecimal("0.70"),
                "rules-v1");
    }
}
