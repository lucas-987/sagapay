package dev.treyer.sagapay.ledger.domain;

/** Unreadable pagination cursor ({@link PostingCursor#decode}) — distinct from
 * {@code IllegalArgumentException} so {@code LedgerRestExceptionHandler} doesn't map
 * it to the same 404 "unknown resource"; it's a 400, not a 404. */
public class InvalidCursorException extends RuntimeException {
    public InvalidCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
