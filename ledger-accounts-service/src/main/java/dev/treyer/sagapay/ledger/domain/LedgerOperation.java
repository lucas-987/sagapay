package dev.treyer.sagapay.ledger.domain;

/** {@code RESERVE}/{@code POST} are idempotent via the {@code ledger_idempotency}
 * table — {@code RELEASE} isn't, the same way: it never touches that table, its
 * idempotence instead comes from the conditional transition {@code UPDATE ... WHERE
 * status = ACTIVE}, a different mechanism based on database state rather than a
 * dedicated table. */
public enum LedgerOperation {
    RESERVE,
    POST,
    RELEASE
}
