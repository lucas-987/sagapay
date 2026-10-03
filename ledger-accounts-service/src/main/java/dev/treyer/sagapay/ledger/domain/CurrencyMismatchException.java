package dev.treyer.sagapay.ledger.domain;

/** Distinct from a bare {@code IllegalArgumentException}, which maps to NOT_FOUND. */
public class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
