package dev.treyer.sagapay.ledger.domain;

/** Its own error code: an unknown handle is not an unknown account id. */
public class UnknownHandleException extends RuntimeException {
    public UnknownHandleException(String handle) {
        super("unknown handle " + handle);
    }
}
