package dev.treyer.sagapay.ledger.domain;

import java.math.BigDecimal;

/** Without this guard, a negative amount passed the funds-available check (trivially
 * true for a negative number) and was only rejected by the reservations table's
 * {@code CHECK (amount > 0)} — a 500 with a leaked persistence stack trace instead of
 * a clean 400. */
public class InvalidAmountException extends IllegalArgumentException {
    public InvalidAmountException(BigDecimal amount) {
        super("amount must be positive: " + amount);
    }
}
