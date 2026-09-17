package dev.treyer.sagapay.ledger.domain;

/** {@code UUID.fromString}/{@code new BigDecimal} on malformed gRPC input throw a
 * bare {@code IllegalArgumentException}, indistinguishable from "unknown account" —
 * a malformed id would otherwise get 404/NOT_FOUND instead of 400/INVALID_ARGUMENT. */
public class MalformedRequestException extends IllegalArgumentException {
    public MalformedRequestException(String field, String value, Throwable cause) {
        super("malformed " + field + ": " + value, cause);
    }
}
