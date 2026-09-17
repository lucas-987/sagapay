package dev.treyer.sagapay.ledger.domain;

/** Extends {@code IllegalArgumentException} for compatibility with existing callers,
 * but stays a distinct class so it maps to its own status: 400/INVALID_ARGUMENT, not
 * the 404/NOT_FOUND used for an unknown account. */
public class CurrencyMismatchException extends IllegalArgumentException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
