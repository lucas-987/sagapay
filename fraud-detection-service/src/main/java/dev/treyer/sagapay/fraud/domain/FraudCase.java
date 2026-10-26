package dev.treyer.sagapay.fraud.domain;

import dev.treyer.sagapay.common.domain.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A flagged transfer; {@code velocitySnapshot} is the history the decision read. */
public record FraudCase(
        UUID transferId,
        UUID senderId,
        Money amount,
        BigDecimal score,
        List<FraudRule> reasons,
        SenderActivity velocitySnapshot) {

    public FraudCase {
        reasons = List.copyOf(reasons);
    }
}
