package dev.treyer.sagapay.ledger.domain;

/** Malformed input must not surface as the bare {@code IllegalArgumentException}
 * that maps to NOT_FOUND. */
public class MalformedRequestException extends IllegalArgumentException {
    public MalformedRequestException(String field, String value, Throwable cause) {
        super("malformed " + field + ": " + value, cause);
    }
}
