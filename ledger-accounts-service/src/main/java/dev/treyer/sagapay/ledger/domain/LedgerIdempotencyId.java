package dev.treyer.sagapay.ledger.domain;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.io.Serializable;
import java.util.Objects;

public class LedgerIdempotencyId implements Serializable {

    // Not a foreign key: transfers live in another service's database.
    private String transferId;

    @Enumerated(EnumType.STRING)
    private LedgerOperation operation;

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
