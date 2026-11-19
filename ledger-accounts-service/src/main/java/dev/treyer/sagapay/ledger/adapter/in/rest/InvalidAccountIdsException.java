package dev.treyer.sagapay.ledger.adapter.in.rest;

/** Distinct from {@code IllegalArgumentException}, which maps to 404: this is a 400. */
public class InvalidAccountIdsException extends RuntimeException {
    public InvalidAccountIdsException(String message) {
        super(message);
    }

    public InvalidAccountIdsException(String message, Throwable cause) {
        super(message, cause);
    }
}
