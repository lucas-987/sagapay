package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.io.Serializable;
import java.util.Objects;

/** {@code @Embeddable}, not {@code @IdClass}: simpler to pass around wherever a row
 * needs to be identified, without duplicating fields between the entity and the key
 * class. {@code equals}/{@code hashCode} are mandatory — required by the JPA spec for
 * any primary key class. */
public class LedgerIdempotencyId implements Serializable {

    // Not a DB FK: `transfers` lives in another service's database (orchestrator_svc).
    private String transferId;

    @Enumerated(EnumType.STRING)
    private LedgerOperation operation;

    /** Required by JPA (field access) — never called by business code. */
    protected LedgerIdempotencyId() {}

    public LedgerIdempotencyId(String transferId, LedgerOperation operation) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    public String getTransferId() {
        return transferId;
    }

    public LedgerOperation getOperation() {
        return operation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LedgerIdempotencyId that)) return false;
        return transferId.equals(that.transferId) && operation == that.operation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(transferId, operation);
    }
}
