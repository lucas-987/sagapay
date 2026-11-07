package dev.treyer.sagapay.fraud.domain;

import java.math.BigDecimal;
import java.util.List;

public record FraudAssessment(BigDecimal score, FraudDecision decision, List<FraudRule> reasons, String modelVersion) {

    public FraudAssessment {
        reasons = List.copyOf(reasons);
    }
}
