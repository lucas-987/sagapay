package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;

public class InvalidAmountException extends IllegalArgumentException {
    public InvalidAmountException(BigDecimal amount) {
        super("amount must be positive: " + amount);
    }
}
