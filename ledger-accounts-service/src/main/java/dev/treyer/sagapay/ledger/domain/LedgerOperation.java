package dev.treyer.sagapay.ledger.domain;

/** No {@code RELEASE}: releasing is idempotent through its conditional status
 * update, not through the idempotency table. */
public enum LedgerOperation {
    RESERVE,
    POST,
    RELEASE
}
