package dev.treyer.sagapay.ledger.domain;

/** Distinct from {@code IllegalArgumentException}, which maps to 404: this is a 400. */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
